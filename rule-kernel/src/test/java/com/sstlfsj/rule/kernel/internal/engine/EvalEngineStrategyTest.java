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
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
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
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);

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
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);

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
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };

        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", SceneExecutionStrategy.FIRST_HIT);
        // priority 倒序：HIGH_RISK(20) 排前，LOW_RISK(5) 排后
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "LOW_RISK", 5),
                snapshot(2L, "t1", "fraud", "HIGH_RISK", 20)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", countingHit), true);

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
                Map.of(), Map.of("AST_BOOLEAN", hitExecutor()), true);
        return engine.evaluate(event("t1", "fraud"));
    }

    @Test
    void allCandidatesBlockedByPreGate_outcomeCarriesBlockedBy_resultMiss() {
        // 候选带 ROLLOUT pre-gate 且被拦截 → passed 空 → outcome.blockedBy=首个阻断 gate，result=miss
        com.sstlfsj.rule.kernel.api.spi.pregate.PreGate gate =
                new com.sstlfsj.rule.kernel.api.spi.pregate.PreGate() {
                    public String gateType() { return "ROLLOUT"; }
                    public com.sstlfsj.rule.kernel.api.model.PreGateResult evaluate(
                            com.sstlfsj.rule.kernel.api.model.PreGateContext ctx) {
                        return com.sstlfsj.rule.kernel.api.model.PreGateResult.blocked("ROLLOUT");
                    }
                };
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "fraud", "t1", EMPTY_AND,
                List.of(new RuleVersionSnapshot.PreGateConfig("ROLLOUT", Map.of())),
                List.of(new RuleVersionSnapshot.DecisionBinding("PASS", 1)),
                List.of(), "AST_BOOLEAN");
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(snap));
        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of("ROLLOUT", gate), Map.of("AST_BOOLEAN", hitExecutor()), true);

        RuleEvent evt = event("t1", "fraud");
        EvalOutcome outcome = engine.evaluateWithContext(evt, engine.match(evt), Instant.now());

        assertEquals("ROLLOUT", outcome.blockedBy());
        assertFalse(outcome.result().ruleHit());
    }

    @Test
    void firstHit_tie_picksLowerRuleVersionId() {
        // 最高 binding priority 相同时，FIRST_HIT 按 ruleVersionId 升序选取（确定可复现）；
        // 反转候选插入顺序结果不变 → 排序不随遍历顺序漂移
        AtomicInteger fwdCount = new AtomicInteger(0);
        EvalResult forward = evalFirstHitCounting(fwdCount,
                snapshot(1L, "t1", "fraud", "DECISION_A", 10),
                snapshot(2L, "t1", "fraud", "DECISION_B", 10));
        AtomicInteger revCount = new AtomicInteger(0);
        EvalResult reversed = evalFirstHitCounting(revCount,
                snapshot(2L, "t1", "fraud", "DECISION_B", 10),
                snapshot(1L, "t1", "fraud", "DECISION_A", 10));

        assertEquals("DECISION_A", forward.finalDecision().code());
        assertEquals("DECISION_A", reversed.finalDecision().code());
        // ruleVersionId=1 排首并命中即短路，只执行 1 次
        assertEquals(1, fwdCount.get());
        assertEquals(1, revCount.get());
    }

    private static EvalResult evalFirstHitCounting(AtomicInteger count, RuleVersionSnapshot... snaps) {
        RuleVersionExecutor countingHit = (snap, ctx) -> {
            count.incrementAndGet();
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().get(0);
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };
        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", SceneExecutionStrategy.FIRST_HIT);
        index.update("t1", "fraud", "*", List.of(snaps));
        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", countingHit), true);
        return engine.evaluate(event("t1", "fraud"));
    }

    @Test
    void firstHit_noMatch_returnsMiss() {
        RuleVersionExecutor missExec = (snap, ctx) ->
                new EvalResult(false, null, List.of(), List.of(), null, null, null, null);

        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", SceneExecutionStrategy.FIRST_HIT);
        index.update("t1", "fraud", "*", List.of(
                snapshot(1L, "t1", "fraud", "BLOCK", 10)));

        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", missExec), true);

        assertFalse(engine.evaluate(event("t1", "fraud")).ruleHit());
    }

    @Test
    void allHits_decisionTree_usesLeafDecisionNotMaxBinding() {
        RuleVersionExecutor treeExec = (s, c) -> {
            Decision pass = new Decision("PASS", "", 10, s.ruleVersionId(), "低危");
            return new EvalResult(true, pass, List.of(pass), List.of(), null, null, "低危", null);
        };
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "fraud", "t1", EMPTY_AND, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 30),
                        new RuleVersionSnapshot.DecisionBinding("PASS", 10)),
                List.of(), "DECISION_TREE");
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "fraud", "*", List.of(snap));
        EvalEngine engine = new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("DECISION_TREE", treeExec), true);
        EvalResult r = engine.evaluate(event("t1", "fraud"));
        assertEquals("PASS", r.finalDecision().code());
        assertEquals("低危", r.finalDecision().category());
        assertEquals("低危", r.category());
    }

    @Test
    void allHits_multipleTrees_eachCategoryPreserved() {
        RuleVersionExecutor dev = (s, c) -> { Decision d = new Decision("REVIEW","",20,s.ruleVersionId(),"中危");
            return new EvalResult(true, d, List.of(d), List.of(), null, null, "中危", null); };
        RuleVersionExecutor amt = (s, c) -> { Decision d = new Decision("REVIEW","",10,s.ruleVersionId(),"大额");
            return new EvalResult(true, d, List.of(d), List.of(), null, null, "大额", null); };
        RuleVersionSnapshot s1 = new RuleVersionSnapshot(1L,"fraud","t1",EMPTY_AND,List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REVIEW",20)),List.of(),"DEV");
        RuleVersionSnapshot s2 = new RuleVersionSnapshot(2L,"fraud","t1",EMPTY_AND,List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REVIEW",10)),List.of(),"AMT");
        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1","fraud",SceneExecutionStrategy.ALL_HITS);
        index.update("t1","fraud","*",List.of(s1,s2));
        EvalEngine engine = new EvalEngine(index,new EvalContextAssembler(List.of(),List.of()),
                Map.of(),Map.of("DEV",dev,"AMT",amt), true);
        EvalResult r = engine.evaluate(event("t1","fraud"));
        List<String> cats = r.hitDecisions().stream().map(Decision::category).sorted().toList();
        assertEquals(List.of("中危","大额"), cats);
        assertEquals("中危", r.finalDecision().category());
    }

    @Test
    void firstHit_booleanRule_winnerFromBindingNotNull() {
        RuleVersionExecutor boolExec = (s, c) -> EvalResult.hit();
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L,"fraud","t1",EMPTY_AND,List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("PASS",5)),List.of(),"AST_BOOLEAN");
        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1","fraud",SceneExecutionStrategy.FIRST_HIT);
        index.update("t1","fraud","*",List.of(snap));
        EvalEngine engine = new EvalEngine(index,new EvalContextAssembler(List.of(),List.of()),
                Map.of(),Map.of("AST_BOOLEAN",boolExec), true);
        EvalResult r = engine.evaluate(event("t1","fraud"));
        assertTrue(r.ruleHit());
        assertNotNull(r.finalDecision());
        assertEquals("PASS", r.finalDecision().code());
        assertNull(r.finalDecision().category());
    }

    @Test
    void firstHit_hitButNoDecisionBinding_isMissNotHit() {
        // 命中但无决策且无 binding：FIRST_HIT 不计命中，与 evaluateAllCandidates「无决策即非命中」对齐
        RuleVersionExecutor boolExec = (s, c) -> EvalResult.hit();
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L,"fraud","t1",EMPTY_AND,List.of(),
                List.of(),List.of(),"AST_BOOLEAN");
        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1","fraud",SceneExecutionStrategy.FIRST_HIT);
        index.update("t1","fraud","*",List.of(snap));
        EvalEngine engine = new EvalEngine(index,new EvalContextAssembler(List.of(),List.of()),
                Map.of(),Map.of("AST_BOOLEAN",boolExec), true);
        assertFalse(engine.evaluate(event("t1","fraud")).ruleHit());
    }
}
