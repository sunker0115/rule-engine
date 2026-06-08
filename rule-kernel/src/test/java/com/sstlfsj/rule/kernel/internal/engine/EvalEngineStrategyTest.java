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

class EvalEngineStrategyTest {

    private static final AndNode EMPTY_AND = new AndNode(List.of(), null, null);

    private static RuleEvent event(String tenantId, String sceneCode) {
        return new RuleEvent(tenantId, sceneCode, "ORDER", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
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
            return new EvalResult(true, d, List.of(d), List.of(), null, List.of(), null, null, null);
        };
    }

    @Test
    void highestPriority_multipleHits_returnsHighest() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "LOW_RISK", 5),
                snapshot(2L, "t1", "fraud", "HIGH_RISK", 20)));
        // 默认策略 HIGHEST_PRIORITY

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()));

        EvalResult result = engine.evaluate(event("t1", "fraud"));
        assertTrue(result.ruleHit());
        assertEquals("HIGH_RISK", result.finalDecision().code());
        assertEquals(2, result.hitDecisions().size());
    }

    @Test
    void allHits_multipleHits_collectsAll() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", SceneExecutionStrategy.ALL_HITS);
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "LOW_RISK", 5),
                snapshot(2L, "t1", "fraud", "HIGH_RISK", 20)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()));

        EvalResult result = engine.evaluate(event("t1", "fraud"));
        assertTrue(result.ruleHit());
        assertEquals(2, result.hitDecisions().size());
        assertEquals("HIGH_RISK", result.finalDecision().code());
    }

    @Test
    void firstHit_shortCircuits_afterFirstMatch() {
        AtomicInteger evalCount = new AtomicInteger(0);
        RuleVersionExecutor countingHit = (snap, ctx) -> {
            evalCount.incrementAndGet();
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().get(0);
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, List.of(), null, null, null);
        };

        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", SceneExecutionStrategy.FIRST_HIT);
        // priority 倒序：HIGH_RISK(20) 排前，LOW_RISK(5) 排后
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "LOW_RISK", 5),
                snapshot(2L, "t1", "fraud", "HIGH_RISK", 20)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", countingHit));

        EvalResult result = engine.evaluate(event("t1", "fraud"));
        assertTrue(result.ruleHit());
        // FIRST_HIT：只执行了 1 次（priority 最高的 HIGH_RISK 命中后停止）
        assertEquals(1, evalCount.get());
        assertEquals("HIGH_RISK", result.finalDecision().code());
        assertEquals(1, result.hitDecisions().size());
    }

    @Test
    void highestPriority_tie_isDeterministic_newerRuleVersionWins() {
        // 两条规则同 priority=10、不同决策码：确定化后恒选 fromRuleVersionId 较大者（规则 2），
        // 且与候选插入顺序无关（反转顺序结果不变 → 证明不随遍历顺序漂移）
        EvalResult forward = evalHighestPriority(
                snapshot(1L, "t1", "fraud", "DECISION_A", 10),
                snapshot(2L, "t1", "fraud", "DECISION_B", 10));
        EvalResult reversed = evalHighestPriority(
                snapshot(2L, "t1", "fraud", "DECISION_B", 10),
                snapshot(1L, "t1", "fraud", "DECISION_A", 10));

        assertEquals("DECISION_B", forward.finalDecision().code());
        assertEquals("DECISION_B", reversed.finalDecision().code());
    }

    private static EvalResult evalHighestPriority(RuleVersionSnapshot... snaps) {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(snaps));
        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()));
        return engine.evaluate(event("t1", "fraud"));
    }

    @Test
    void firstHit_noMatch_returnsMiss() {
        RuleVersionExecutor missExec = (snap, ctx) ->
                new EvalResult(false, null, List.of(), List.of(), null, List.of(), null, null, null);

        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", SceneExecutionStrategy.FIRST_HIT);
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "BLOCK", 10)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", missExec));

        assertFalse(engine.evaluate(event("t1", "fraud")).ruleHit());
    }
}
