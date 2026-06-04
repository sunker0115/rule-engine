package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.util.*;

/**
 * 纯 Java 评估编排器：matcher → pre-gate → context → executor。
 * 无副作用，不写 DB，不派发 Action，不依赖 Spring。
 */
public class EvalEngine {

    private static final String DEFAULT_EXECUTOR_KEY = "AST_BOOLEAN";

    private final SceneRuleIndex index;
    private final EvalContextAssembler contextAssembler;
    private final Map<String, PreGate> preGates;
    private final Map<String, RuleVersionExecutor> executors;

    /**
     * @param index           倒排索引，供 matcher 阶段查询候选快照
     * @param contextAssembler 装配 EvalContext（主体、指标）
     * @param preGates        Pre-Gate 映射，key = gateType
     * @param executors       executor 映射，key = kind（如 "AST_BOOLEAN"）
     */
    public EvalEngine(SceneRuleIndex index,
                      EvalContextAssembler contextAssembler,
                      Map<String, PreGate> preGates,
                      Map<String, RuleVersionExecutor> executors) {
        this.index = index;
        this.contextAssembler = contextAssembler;
        this.preGates = Map.copyOf(preGates);
        this.executors = Map.copyOf(executors);
    }

    /**
     * 对单个事件求值，从索引查询候选快照后执行完整评估链路。
     *
     * @param event 触发事件
     * @return 纯计算结果，无副作用
     */
    public EvalResult evaluate(RuleEvent event) {
        List<RuleVersionSnapshot> candidates =
                index.match(event.tenantId(), event.sceneCode(), event.eventType());
        SceneExecutionStrategy strategy = index.getStrategy(event.tenantId(), event.sceneCode());
        return evaluate(event, candidates, strategy);
    }

    /**
     * 对指定候选快照列表求值，跳过索引查找步骤。
     * 供 dry-run 路径：直接传入从 DB 加载的单条快照，使用 HIGHEST_PRIORITY 策略。
     *
     * @param event      触发事件
     * @param candidates 候选快照列表
     * @return 纯计算结果，无副作用
     */
    public EvalResult evaluate(RuleEvent event, List<RuleVersionSnapshot> candidates) {
        return evaluate(event, candidates, SceneExecutionStrategy.HIGHEST_PRIORITY);
    }

    private EvalResult evaluate(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                SceneExecutionStrategy strategy) {
        if (candidates.isEmpty()) return EvalResult.miss();

        List<RuleVersionSnapshot> passed = new ArrayList<>();
        for (RuleVersionSnapshot snap : candidates) {
            if (applyPreGates(event, snap) == null) passed.add(snap);
        }
        if (passed.isEmpty()) return EvalResult.miss();

        EvalContext ctx = contextAssembler.assemble(event, passed);

        if (strategy == SceneExecutionStrategy.FIRST_HIT) {
            return evaluateFirstHit(event, passed, ctx);
        }
        // HIGHEST_PRIORITY / ALL_HITS：全量评估，两者语义相同（均收集所有命中决策）
        return evaluateAllCandidates(passed, ctx);
    }

    /** FIRST_HIT：按快照最高 decisionBinding priority 倒序，第一条命中即返回。 */
    private EvalResult evaluateFirstHit(RuleEvent event,
                                        List<RuleVersionSnapshot> passed, EvalContext ctx) {
        List<RuleVersionSnapshot> sorted = passed.stream()
                .sorted(Comparator.comparingInt(
                        (RuleVersionSnapshot s) -> s.decisionBindings().stream()
                                .mapToInt(RuleVersionSnapshot.DecisionBinding::priority)
                                .max().orElse(0))
                        .reversed())
                .toList();

        for (RuleVersionSnapshot snap : sorted) {
            try {
                EvalResult r = selectExecutor(snap).execute(snap, ctx);
                if (r.ruleHit()) {
                    Decision winner = r.hitDecisions().stream()
                            .max(Comparator.comparingInt(Decision::priority))
                            .orElse(r.finalDecision());
                    return new EvalResult(true, winner, List.of(winner),
                            r.nodeTrace(), r.errorCode(), List.of(), r.score());
                }
            } catch (Exception ignored) {
            }
        }
        return EvalResult.miss();
    }

    /** HIGHEST_PRIORITY / ALL_HITS：全量评估，收集所有命中决策。 */
    private EvalResult evaluateAllCandidates(List<RuleVersionSnapshot> passed, EvalContext ctx) {
        List<Decision> hitDecisions = new ArrayList<>();
        List<NodeTrace> allTraces   = new ArrayList<>();
        String errorCode = null;
        Double aggregatedScore = null;

        for (RuleVersionSnapshot snap : passed) {
            try {
                RuleVersionExecutor exec = selectExecutor(snap);
                EvalResult r = exec.execute(snap, ctx);
                allTraces.addAll(r.nodeTrace());
                if (r.ruleHit()) {
                    snap.decisionBindings().stream()
                            .max(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                            .ifPresent(b -> hitDecisions.add(
                                    new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId())));
                }
                if (r.errorCode() != null && errorCode == null) errorCode = r.errorCode();
                if (r.score() != null) {
                    aggregatedScore = aggregatedScore == null ? r.score()
                            : Math.max(aggregatedScore, r.score());
                }
            } catch (Exception e) {
                if (errorCode == null) errorCode = "CONDITION_EVAL_ERROR";
            }
        }

        Decision finalDecision = hitDecisions.stream()
                .max(Comparator.comparingInt(Decision::priority))
                .orElse(null);

        return new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                List.copyOf(hitDecisions),
                List.copyOf(allTraces),
                errorCode,
                List.of(),
                aggregatedScore
        );
    }

    /** 按快照 kind 选择 executor；找不到时回退到 AST_BOOLEAN。 */
    private RuleVersionExecutor selectExecutor(RuleVersionSnapshot snap) {
        String key = snap.kind() != null ? snap.kind() : DEFAULT_EXECUTOR_KEY;
        RuleVersionExecutor exec = executors.get(key);
        if (exec == null) exec = executors.get(DEFAULT_EXECUTOR_KEY);
        if (exec == null) exec = executors.values().iterator().next();
        return exec;
    }

    /**
     * 对单条候选快照执行所有 Pre-Gate。
     *
     * @return null 表示全部通过；非 null 为首个阻断的 gate 类型
     */
    private String applyPreGates(RuleEvent event, RuleVersionSnapshot snap) {
        for (RuleVersionSnapshot.PreGateConfig cfg : snap.preGates()) {
            PreGate gate = preGates.get(cfg.gateType());
            if (gate == null) continue;
            PreGateContext pCtx = new PreGateContext(
                    event.tenantId(), event.sceneCode(), event.subjectId(),
                    event, snap.ruleVersionId(), cfg.params());
            PreGateResult result = gate.evaluate(pCtx);
            if (!result.passed()) return result.blockedBy();
        }
        return null;
    }
}
