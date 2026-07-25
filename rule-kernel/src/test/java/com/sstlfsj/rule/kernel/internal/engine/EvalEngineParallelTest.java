package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecutionMode.PARALLEL 求值测试：ALL_HITS 全量并行 / HIGHEST_PRIORITY 并行 / FIRST_HIT 批式并行 /
 * 异常与 errorCode 传播 / SEQUENTIAL 行为不变回归 / SceneRuleIndex.defaultParams 集成。
 * 串行分支的 strategy 测试见 {@link EvalEngineStrategyTest}。
 */
class EvalEngineParallelTest {

    private static final AndNode EMPTY_AND = new AndNode(List.of(), null, null);

    private static RuleEvent event(String tenantId, String sceneCode) {
        return new RuleEvent(tenantId, sceneCode, "ORDER", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
    }

    private static RuleVersionSnapshot snapshot(Long id, String tenantId, String sceneCode,
                                                 String decisionCode, int priority) {
        return new RuleVersionSnapshot(id, sceneCode, tenantId,
                EMPTY_AND, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding(decisionCode, priority)),
                List.of(), "AST_BOOLEAN");
    }

    /** 总是命中，返回快照 decisionBindings 中最高优先级决策 */
    private static RuleVersionExecutor hitExecutor() {
        return (snap, ctx) -> {
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().stream()
                    .max(java.util.Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                    .orElseThrow();
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };
    }

    // ── ALL_HITS PARALLEL ──

    @Test
    void allHitsParallel_5rules_allHit() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "A", 1),
                snapshot(2L, "t1", "fraud", "B", 2),
                snapshot(3L, "t1", "fraud", "C", 3),
                snapshot(4L, "t1", "fraud", "D", 4),
                snapshot(5L, "t1", "fraud", "E", 5)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);

        RuleEvent evt = event("t1", "fraud");
        EvalResult result = engine.evaluateWithContext(evt, engine.match(evt),
                SceneExecutionStrategy.ALL_HITS, ExecutionMode.PARALLEL, Instant.now()).result();

        assertTrue(result.ruleHit());
        assertEquals(5, result.hitDecisions().size(), "5 条规则全命中，全部收集进 hitDecisions");
        assertEquals("E", result.finalDecision().code(), "最高 priority E(5) 胜出");
    }

    @Test
    void allHitsParallel_emptyCandidates_returnsMiss() {
        SceneRuleIndex index = new SceneRuleIndex();
        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);

        RuleEvent evt = event("t1", "fraud");
        EvalOutcome outcome = engine.evaluateWithContext(evt, List.of(),
                SceneExecutionStrategy.ALL_HITS, ExecutionMode.PARALLEL, Instant.now());

        assertFalse(outcome.result().ruleHit());
        assertNull(outcome.context(), "空候选不构建上下文");
    }

    @Test
    void allHitsParallel_countingExecutor_runsAllCandidates() {
        // ALL_HITS + PARALLEL：所有候选都执行，无一短路（与 FIRST_HIT 不同）
        AtomicInteger count = new AtomicInteger(0);
        RuleVersionExecutor countingHit = (snap, ctx) -> {
            count.incrementAndGet();
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().get(0);
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };

        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "LOW", 1),
                snapshot(2L, "t1", "fraud", "MID", 5),
                snapshot(3L, "t1", "fraud", "HIGH", 10)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", countingHit), true);

        engine.evaluateWithContext(event("t1", "fraud"), engine.match(event("t1", "fraud")),
                SceneExecutionStrategy.ALL_HITS, ExecutionMode.PARALLEL, Instant.now());

        assertEquals(3, count.get(), "ALL_HITS 并行执行全量 3 条，无一短路");
    }

    // ── FIRST_HIT 批式并行 ──

    @Test
    void firstHitParallel_batchAll_runsAllInFirstBatch_returnsBest() {
        // FIRST_HIT + PARALLEL：首批全量并行跑（batchSize=candidates.size），选最高 priority 命中；
        // 所有候选都被执行（并行无短路），但只返回最高 priority 的结果
        AtomicInteger count = new AtomicInteger(0);
        RuleVersionExecutor countingHit = (snap, ctx) -> {
            count.incrementAndGet();
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().get(0);
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };

        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "LOW", 5),
                snapshot(2L, "t1", "fraud", "MID", 10),
                snapshot(3L, "t1", "fraud", "HIGH", 20)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", countingHit), true);

        EvalResult result = engine.evaluateWithContext(event("t1", "fraud"),
                engine.match(event("t1", "fraud")),
                SceneExecutionStrategy.FIRST_HIT, ExecutionMode.PARALLEL, Instant.now()).result();

        assertTrue(result.ruleHit());
        assertEquals("HIGH", result.finalDecision().code(), "最高 priority 命中");
        assertEquals(3, count.get(), "FIRST_HIT 批式并行：首批全量 3 条并行跑（不短路），选最佳命中");
        assertEquals(1, result.hitDecisions().size(), "只返回最佳命中的 1 条决策");
    }

    @Test
    void firstHitParallel_allMiss_returnsMiss() {
        RuleVersionExecutor missExec = (snap, ctx) ->
                new EvalResult(false, null, List.of(), List.of(), null, null, null, null);

        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "A", 10),
                snapshot(2L, "t1", "fraud", "B", 5)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", missExec), true);

        EvalResult result = engine.evaluateWithContext(event("t1", "fraud"),
                engine.match(event("t1", "fraud")),
                SceneExecutionStrategy.FIRST_HIT, ExecutionMode.PARALLEL, Instant.now()).result();

        assertFalse(result.ruleHit());
    }

    // ── HIGHEST_PRIORITY PARALLEL ──

    @Test
    void highestPriorityParallel_multipleHits_returnsHighest() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "LOW", 3),
                snapshot(2L, "t1", "fraud", "MID", 7),
                snapshot(3L, "t1", "fraud", "TOP", 15)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);

        EvalResult result = engine.evaluateWithContext(event("t1", "fraud"),
                engine.match(event("t1", "fraud")),
                SceneExecutionStrategy.HIGHEST_PRIORITY, ExecutionMode.PARALLEL, Instant.now()).result();

        assertTrue(result.ruleHit());
        assertEquals("TOP", result.finalDecision().code());
        assertEquals(3, result.hitDecisions().size(), "全量命中全部收集");
    }

    // ── 异常与 errorCode 传播 ──

    @Test
    void parallel_executorThrows_errorCodeCaptured_othersStillRun() {
        // 一条规则执行期抛 RuntimeException：errorCode 被捕获，其他成功的规则结果仍被收集
        AtomicInteger successCount = new AtomicInteger(0);
        RuleVersionExecutor mixedExec = (snap, ctx) -> {
            if (snap.ruleVersionId() == 2L) throw new RuntimeException("boom");
            successCount.incrementAndGet();
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().get(0);
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };

        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "A", 1),
                snapshot(2L, "t1", "fraud", "B", 10),  // 这条会抛异常
                snapshot(3L, "t1", "fraud", "C", 5)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", mixedExec), true);

        EvalResult result = engine.evaluateWithContext(event("t1", "fraud"),
                engine.match(event("t1", "fraud")),
                SceneExecutionStrategy.ALL_HITS, ExecutionMode.PARALLEL, Instant.now()).result();

        assertEquals(EvalErrorCode.CONDITION_EVAL_ERROR.name(), result.errorCode(),
                "异常规则应传播 errorCode");
        assertTrue(result.ruleHit(), "其余成功规则命中不应被吞掉");
        assertEquals(2, successCount.get(), "2 条规则应成功执行（非抛出异常的）");
        assertEquals(2, result.hitDecisions().size(), "2 条成功规则的命中应被收集");
    }

    @Test
    void parallel_executorReturnsError_errorCodeCaptured() {
        // 一条规则返回 errorCode（非抛异常），应在汇聚时合并
        RuleVersionExecutor mixedExec = (snap, ctx) -> {
            if (snap.ruleVersionId() == 2L)
                return new EvalResult(false, null, List.of(), List.of(),
                        EvalErrorCode.METRIC_FETCH_FAIL.name(), null, null, null);
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().get(0);
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };

        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "A", 1),
                snapshot(2L, "t1", "fraud", "B", 10)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", mixedExec), true);

        EvalResult result = engine.evaluateWithContext(event("t1", "fraud"),
                engine.match(event("t1", "fraud")),
                SceneExecutionStrategy.ALL_HITS, ExecutionMode.PARALLEL, Instant.now()).result();

        assertEquals(EvalErrorCode.METRIC_FETCH_FAIL.name(), result.errorCode(),
                "第一条非 exception errorCode 应被捕获");
        assertTrue(result.ruleHit(), "其余命中不应受影响");
    }

    // ── SEQUENTIAL 行为不变回归 ──

    @Test
    void sequential_allHits_unchangedBehavior() {
        // 显式 SEQUENTIAL 模式下行为与 EvalEngineStrategyTest 一致
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "LOW", 1),
                snapshot(2L, "t1", "fraud", "HIGH", 10)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);

        EvalResult result = engine.evaluateWithContext(event("t1", "fraud"),
                engine.match(event("t1", "fraud")),
                SceneExecutionStrategy.ALL_HITS, ExecutionMode.SEQUENTIAL, Instant.now()).result();

        assertTrue(result.ruleHit());
        assertEquals(2, result.hitDecisions().size());
        assertEquals("HIGH", result.finalDecision().code());
    }

    @Test
    void sequential_firstHit_shortCircuits() {
        // SEQUENTIAL FIRST_HIT 应短路：第一条命中即停
        AtomicInteger count = new AtomicInteger(0);
        RuleVersionExecutor countingHit = (snap, ctx) -> {
            count.incrementAndGet();
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().get(0);
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };

        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "LOW", 5),
                snapshot(2L, "t1", "fraud", "HIGH", 20)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", countingHit), true);

        engine.evaluateWithContext(event("t1", "fraud"), engine.match(event("t1", "fraud")),
                SceneExecutionStrategy.FIRST_HIT, ExecutionMode.SEQUENTIAL, Instant.now());

        assertEquals(1, count.get(), "SEQUENTIAL FIRST_HIT：priority 最高者命中后短路，只执行 1 条");
    }

    // ── SceneRuleIndex.defaultParams 集成 ──

    @Test
    void integration_defaultModeFromIndex_triggersParallel() {
        // 经 evaluate() 便捷入口：mode 从 SceneRuleIndex.getMode() 读取 defaultParams.executionMode
        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", SceneExecutionStrategy.ALL_HITS);
        index.setDefaultParams("t1", "fraud", Map.of("executionMode", "PARALLEL"));
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "A", 1),
                snapshot(2L, "t1", "fraud", "B", 2),
                snapshot(3L, "t1", "fraud", "C", 3)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);

        EvalResult result = engine.evaluate(event("t1", "fraud"));

        assertTrue(result.ruleHit());
        assertEquals(3, result.hitDecisions().size(), "PARALLEL 模式经 defaultParams 集成：3 条全收");
    }

    @Test
    void integration_defaultModeFromIndex_absentDefaultsToSequential() {
        // defaultParams 无 executionMode → 默认 SEQUENTIAL，行为不变
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "X", 10)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);

        EvalResult result = engine.evaluate(event("t1", "fraud"));

        assertTrue(result.ruleHit());
        assertEquals("X", result.finalDecision().code());
    }

    // ── 无 executor 注册时 fallback ──

    @Test
    void parallel_unregisteredKind_fallsBackToAstBoolean() {
        // kind "UNKNOWN" 无对应 executor → fallback 到 AST_BOOLEAN
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                new RuleVersionSnapshot(1L, "fraud", "t1", EMPTY_AND, List.of(),
                        List.of(new RuleVersionSnapshot.DecisionBinding("OK", 5)),
                        List.of(), "UNKNOWN")));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);

        EvalResult result = engine.evaluateWithContext(event("t1", "fraud"),
                engine.match(event("t1", "fraud")),
                SceneExecutionStrategy.ALL_HITS, ExecutionMode.PARALLEL, Instant.now()).result();

        assertTrue(result.ruleHit(), "unregistered kind 应 fallback 到 AST_BOOLEAN executor");
    }
}
