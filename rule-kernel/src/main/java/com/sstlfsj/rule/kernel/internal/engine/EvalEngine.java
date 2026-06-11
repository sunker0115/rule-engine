package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.time.Instant;
import java.util.*;

/**
 * 纯 Java 评估编排器：matcher → pre-gate → context → executor。
 * 无副作用，不写 DB，不派发 Action，不依赖 Spring。
 */
public class EvalEngine {

    private static final String DEFAULT_EXECUTOR_KEY = RuleKind.AST_BOOLEAN.tag();

    /**
     * 决策优先级裁决：priority 越大越优先；priority 相同时按 fromRuleVersionId 较大（较新规则版本）胜。
     * 二级键保证平局结果确定可复现，不随候选遍历顺序（索引/DB 返回序）漂移。
     */
    private static final Comparator<Decision> DECISION_PRECEDENCE =
            Comparator.comparingInt(Decision::priority)
                    .thenComparing(Decision::fromRuleVersionId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    /** FIRST_HIT 候选排序：最高 binding priority 倒序，平局按 ruleVersionId 升序（确定可复现）。 */
    private static final Comparator<RuleVersionSnapshot> FIRST_HIT_ORDER =
            Comparator.comparingInt(EvalEngine::maxBindingPriority).reversed()
                    .thenComparingLong(RuleVersionSnapshot::ruleVersionId);

    /** binding 回退裁决：priority 越大越优先，平局按 decisionCode 字典序。 */
    private static final Comparator<RuleVersionSnapshot.DecisionBinding> BINDING_PRECEDENCE =
            Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority)
                    .thenComparing(RuleVersionSnapshot.DecisionBinding::decisionCode);

    private final SceneRuleIndex index;
    private final EvalContextAssembler contextAssembler;
    private final Map<String, PreGate> preGates;
    private final Map<String, RuleVersionExecutor> executors;
    private final boolean traceEnabled;

    /**
     * @param index            倒排索引，供 matcher 阶段查询候选快照
     * @param contextAssembler 装配 EvalContext（主体、指标）
     * @param preGates         Pre-Gate 映射，key = gateType
     * @param executors        executor 映射，key = kind（如 {@link RuleKind#AST_BOOLEAN} 的 tag）
     * @param traceEnabled     常规评估是否收集 NodeTrace 的全局默认（dry-run 走显式形参强制 true）
     */
    public EvalEngine(SceneRuleIndex index,
                      EvalContextAssembler contextAssembler,
                      Map<String, PreGate> preGates,
                      Map<String, RuleVersionExecutor> executors,
                      boolean traceEnabled) {
        this.index = index;
        this.contextAssembler = contextAssembler;
        this.preGates = Map.copyOf(preGates);
        this.executors = Map.copyOf(executors);
        this.traceEnabled = traceEnabled;
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
     * 用显式策略评估给定候选，trace 收集走全局 {@code traceEnabled} 默认。
     *
     * @param event      触发事件
     * @param candidates 候选快照
     * @param strategy   执行策略
     * @param now        本次评估统一时刻
     * @return 结果与上下文的聚合；早返回 miss 时 context 为 null
     */
    public EvalOutcome evaluateWithContext(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                           SceneExecutionStrategy strategy, Instant now) {
        return evaluateWithContext(event, candidates, strategy, now, traceEnabled);
    }

    /**
     * 用显式策略评估给定候选，并以 {@code collectTrace} 显式控制本次评估是否收集 NodeTrace
     * （dry-run 传 true 强制收集，常规链路传 {@code traceEnabled}）。
     * 仅在真正调用执行器的策略 switch 外层绑定 {@link TraceScope#COLLECT}；早返回 miss / blockedBy
     * 不执行执行器，故不在绑定范围内。
     *
     * @param event        触发事件
     * @param candidates   候选快照
     * @param strategy     执行策略
     * @param now          本次评估统一时刻
     * @param collectTrace 本次评估是否收集 NodeTrace
     * @return 结果与上下文的聚合；早返回 miss 时 context 为 null
     */
    public EvalOutcome evaluateWithContext(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                           SceneExecutionStrategy strategy, Instant now,
                                           boolean collectTrace) {
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

        // 仅执行器调用绑定 COLLECT：执行器读 TraceScope.COLLECT 决定是否构建 trace
        EvalResult result;
        try {
            result = ScopedValue.where(TraceScope.COLLECT, collectTrace).call(() -> switch (strategy) {
                case FIRST_HIT -> evaluateFirstHit(event, passed, ctx);
                // HIGHEST_PRIORITY / ALL_HITS：语义相同，均全量评估收集所有命中决策
                case HIGHEST_PRIORITY, ALL_HITS -> evaluateAllCandidates(passed, ctx);
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // switch 分支无受检异常，CallableOp 声明的 Exception 不会真正发生
            throw new IllegalStateException(e);
        }
        return new EvalOutcome(result, ctx);
    }

    /** FIRST_HIT：按快照最高 decisionBinding priority 倒序，第一条命中即返回。 */
    private EvalResult evaluateFirstHit(RuleEvent event,
                                        List<RuleVersionSnapshot> passed, EvalContext ctx) {
        // 平局确定化：最高 binding priority 相同时按 ruleVersionId 升序，保证 FIRST_HIT 选取稳定可复现
        List<RuleVersionSnapshot> sorted = new ArrayList<>(passed);
        sorted.sort(FIRST_HIT_ORDER);

        for (RuleVersionSnapshot snap : sorted) {
            try {
                EvalResult r = selectExecutor(snap).execute(snap, ctx);
                if (r.ruleHit()) {
                    List<Decision> decisions = resolveRuleDecisions(snap, r);
                    Decision winner = decisions.isEmpty() ? null
                            : Collections.max(decisions, DECISION_PRECEDENCE);
                    // winner==null（命中但无决策/无 binding）不计 FIRST_HIT，与 evaluateAllCandidates 的「无决策即非命中」一致
                    if (winner == null) continue;
                    return new EvalResult(true, winner, List.of(winner),
                            r.nodeTrace(), r.errorCode(), r.score(),
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
                if (errorCode == null) errorCode = EvalErrorCode.CONDITION_EVAL_ERROR;
            }
        }

        // 免 stream 管道分配：非空时取最大,空则 null（与原 orElse(null) 等价）
        Decision finalDecision = hitDecisions.isEmpty() ? null
                : Collections.max(hitDecisions, DECISION_PRECEDENCE);

        // 局部 list 出方法即无其它引用,用不可变视图免去 List.copyOf 的数组拷贝（allTraces 在高候选数下是热点分配）
        return new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                Collections.unmodifiableList(hitDecisions),
                Collections.unmodifiableList(allTraces),
                errorCode,
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
        List<RuleVersionSnapshot.DecisionBinding> bindings = snap.decisionBindings();
        if (bindings.isEmpty()) return List.of();
        RuleVersionSnapshot.DecisionBinding best = bindings.get(0);
        for (int i = 1; i < bindings.size(); i++) {
            RuleVersionSnapshot.DecisionBinding b = bindings.get(i);
            if (BINDING_PRECEDENCE.compare(b, best) > 0) best = b;
        }
        return List.of(new Decision(best.decisionCode(), best.name(), best.priority(),
                snap.ruleVersionId(), snap.code(), snap.version(), null));
    }

    /** 快照 decisionBindings 的最高 priority；无 binding 时返回 0。供 FIRST_HIT 排序用。 */
    private static int maxBindingPriority(RuleVersionSnapshot snap) {
        List<RuleVersionSnapshot.DecisionBinding> bindings = snap.decisionBindings();
        if (bindings.isEmpty()) return 0;
        int max = Integer.MIN_VALUE;
        for (RuleVersionSnapshot.DecisionBinding b : bindings) {
            if (b.priority() > max) max = b.priority();
        }
        return max;
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
            // fail-closed:未注册的 gateType 视为拦截(blockedBy=gateType),不静默放行——
            // 配了已砍/未实装的 gate 应被挡住而非漏过(发布期已拒此类配置,此为运行期兜底)
            if (gate == null) return cfg.gateType();
            PreGateContext pCtx = new PreGateContext(
                    event.tenantId(), event.sceneCode(), event.subjectId(),
                    event, snap.ruleVersionId(), cfg.params());
            PreGateResult result = gate.evaluate(pCtx);
            if (!result.passed()) return result.blockedBy();
        }
        return null;
    }
}
