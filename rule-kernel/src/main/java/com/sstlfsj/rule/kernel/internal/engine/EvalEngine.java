package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.time.Instant;
import java.util.*;

/**
 * 纯 Java 评估编排器：matcher → pre-gate → context → executor。
 * 无副作用，不写 DB，不派发 Action，不依赖 Spring。
 */
public class EvalEngine {

    private static final String DEFAULT_EXECUTOR_KEY = "AST_BOOLEAN";

    /**
     * 决策优先级裁决：priority 越大越优先；priority 相同时按 fromRuleVersionId 较大（较新规则版本）胜。
     * 二级键保证平局结果确定可复现，不随候选遍历顺序（索引/DB 返回序）漂移。
     */
    private static final Comparator<Decision> DECISION_PRECEDENCE =
            Comparator.comparingInt(Decision::priority)
                    .thenComparing(Decision::fromRuleVersionId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    private final SceneRuleIndex index;
    private final EvalContextAssembler contextAssembler;
    private final Map<String, PreGate> preGates;
    private final Map<String, RuleVersionExecutor> executors;

    /**
     * @param index            倒排索引，供 matcher 阶段查询候选快照
     * @param contextAssembler 装配 EvalContext（主体、指标）
     * @param preGates         Pre-Gate 映射，key = gateType
     * @param executors        executor 映射，key = kind（如 "AST_BOOLEAN"）
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

    /** 标准入口：match → 评估，注入一次评估时刻 now，整棵 AST 共用。 */
    public EvalResult evaluate(RuleEvent event) {
        return evaluateWithContext(event, match(event), Instant.now()).result();
    }

    /**
     * 匹配候选快照（倒排索引查询，廉价内存操作，无副作用）。
     *
     * @param event 触发事件
     * @return 命中的候选快照列表（未过 Pre-Gate）
     */
    public List<RuleVersionSnapshot> match(RuleEvent event) {
        return index.match(event.tenantId(), event.sceneCode(), event.eventType());
    }

    /**
     * 用场景配置策略评估给定候选，返回结果 + 组装好的上下文。
     *
     * @param event      触发事件
     * @param candidates 候选快照（通常来自 {@link #match}）
     * @param now        本次评估统一时刻
     * @return 结果与上下文的聚合；早返回 miss 时 context 为 null
     */
    public EvalOutcome evaluateWithContext(RuleEvent event,
                                           List<RuleVersionSnapshot> candidates, Instant now) {
        SceneExecutionStrategy strategy = index.getStrategy(event.tenantId(), event.sceneCode());
        return evaluateWithContext(event, candidates, strategy, now);
    }

    /**
     * 用显式策略评估给定候选，返回结果 + 组装好的上下文（dry-run 传 HIGHEST_PRIORITY）。
     *
     * @param event      触发事件
     * @param candidates 候选快照
     * @param strategy   执行策略
     * @param now        本次评估统一时刻
     * @return 结果与上下文的聚合；早返回 miss 时 context 为 null
     */
    public EvalOutcome evaluateWithContext(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                           SceneExecutionStrategy strategy, Instant now) {
        if (candidates.isEmpty()) return new EvalOutcome(EvalResult.miss(), null);

        List<RuleVersionSnapshot> passed = new ArrayList<>();
        String firstBlockedBy = null;
        for (RuleVersionSnapshot snap : candidates) {
            String blockedBy = applyPreGates(event, snap);
            if (blockedBy == null) passed.add(snap);
            else if (firstBlockedBy == null) firstBlockedBy = blockedBy;
        }
        // 候选全被 Pre-Gate 拦截：BLOCKED 第四态（D22），blockedBy 记首个阻断 gate；区别于评估后 MISS
        if (passed.isEmpty()) return new EvalOutcome(EvalResult.miss(), null, firstBlockedBy);

        EvalContext ctx = contextAssembler.assemble(event, passed, now);

        EvalResult result = switch (strategy) {
            case FIRST_HIT -> evaluateFirstHit(event, passed, ctx);
            // HIGHEST_PRIORITY / ALL_HITS：语义相同，均全量评估收集所有命中决策
            case HIGHEST_PRIORITY, ALL_HITS -> evaluateAllCandidates(passed, ctx);
        };
        return new EvalOutcome(result, ctx);
    }

    /** FIRST_HIT：按快照最高 decisionBinding priority 倒序，第一条命中即返回。 */
    private EvalResult evaluateFirstHit(RuleEvent event,
                                        List<RuleVersionSnapshot> passed, EvalContext ctx) {
        // 平局确定化：最高 binding priority 相同时按 ruleVersionId 升序，保证 FIRST_HIT 选取稳定可复现
        List<RuleVersionSnapshot> sorted = passed.stream()
                .sorted(Comparator.comparingInt(EvalEngine::maxBindingPriority).reversed()
                        .thenComparingLong(RuleVersionSnapshot::ruleVersionId))
                .toList();

        for (RuleVersionSnapshot snap : sorted) {
            try {
                EvalResult r = selectExecutor(snap).execute(snap, ctx);
                if (r.ruleHit()) {
                    Decision winner = resolveRuleDecisions(snap, r).stream()
                            .max(DECISION_PRECEDENCE)
                            .orElse(null);
                    // winner==null（命中但无决策/无 binding）不计 FIRST_HIT，与 evaluateAllCandidates 的「无决策即非命中」一致
                    if (winner == null) continue;
                    return new EvalResult(true, winner, List.of(winner),
                            r.nodeTrace(), r.errorCode(), List.of(), r.score(),
                            winner.category(), null);
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
                    hitDecisions.addAll(resolveRuleDecisions(snap, r));
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
                .max(DECISION_PRECEDENCE)
                .orElse(null);

        return new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                List.copyOf(hitDecisions),
                List.copyOf(allTraces),
                errorCode,
                List.of(),
                aggregatedScore,
                finalDecision != null ? finalDecision.category() : null,
                null
        );
    }

    /**
     * 一条命中规则贡献的决策：executor 自选了决策（tree/table，hitDecisions 非空）就用它（带 category）；
     * 否则（boolean/scorecard）回退按最高优先级 binding 赋决策（category=null）。
     */
    private static List<Decision> resolveRuleDecisions(RuleVersionSnapshot snap, EvalResult r) {
        if (!r.hitDecisions().isEmpty()) return r.hitDecisions();
        return snap.decisionBindings().stream()
                .max(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority)
                        .thenComparing(RuleVersionSnapshot.DecisionBinding::decisionCode))
                .map(b -> List.<Decision>of(
                        new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId())))
                .orElse(List.of());
    }

    /** 快照 decisionBindings 的最高 priority；无 binding 时返回 0。供 FIRST_HIT 排序用。 */
    private static int maxBindingPriority(RuleVersionSnapshot snap) {
        return snap.decisionBindings().stream()
                .mapToInt(RuleVersionSnapshot.DecisionBinding::priority)
                .max().orElse(0);
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
