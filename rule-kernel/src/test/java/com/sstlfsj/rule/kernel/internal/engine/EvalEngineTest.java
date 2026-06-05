package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

// 策略分支（switch: FIRST_HIT / HIGHEST_PRIORITY / ALL_HITS）测试见 EvalEngineStrategyTest

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalEngineTest {

    private static final AndNode EMPTY_AND = new AndNode(List.of(), null, null);

    private static RuleEvent event(String tenantId, String sceneCode, String eventType) {
        return new RuleEvent(tenantId, sceneCode, eventType, "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of());
    }

    private static RuleVersionSnapshot snapshot(Long id, String tenantId, String sceneCode) {
        return new RuleVersionSnapshot(id, sceneCode, tenantId,
                EMPTY_AND, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of(), "AST_BOOLEAN");
    }

    private static RuleVersionExecutor hitExecutor() {
        return (snap, ctx) -> new EvalResult(true,
                new Decision("BLOCK", "", 10, snap.ruleVersionId()),
                List.of(new Decision("BLOCK", "", 10, snap.ruleVersionId())),
                List.of(), null, List.of(), null, null, null);
    }

    private static RuleVersionExecutor missExecutor() {
        return (snap, ctx) -> new EvalResult(false, null, List.of(), List.of(), null, List.of(), null, null, null);
    }

    private static PreGate blockingGate(String type) {
        return new PreGate() {
            @Override public String gateType() { return type; }
            @Override public PreGateResult evaluate(PreGateContext ctx) {
                return new PreGateResult(false, type);
            }
        };
    }

    @Test
    void evaluate_noMatchInIndex_returnsMiss() {
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(new SceneRuleIndex(), asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()));
        assertFalse(engine.evaluate(event("t1", "scene", "ORDER")).ruleHit());
    }

    @Test
    void evaluate_matchWithHit_returnsDecision() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "scene", "*", List.of(snapshot(1L, "t1", "scene")));

        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()));

        EvalResult result = engine.evaluate(event("t1", "scene", "ORDER"));
        assertTrue(result.ruleHit());
        assertNotNull(result.finalDecision());
        assertEquals("BLOCK", result.finalDecision().code());
    }

    @Test
    void evaluate_matchWithMiss_returnsMiss() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "scene", "*", List.of(snapshot(1L, "t1", "scene")));

        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm, Map.of(),
                Map.of("AST_BOOLEAN", missExecutor()));
        assertFalse(engine.evaluate(event("t1", "scene", "ORDER")).ruleHit());
    }

    @Test
    void evaluate_preGateBlocks_returnsMiss() {
        SceneRuleIndex index = new SceneRuleIndex();
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1",
                EMPTY_AND,
                List.of(new RuleVersionSnapshot.PreGateConfig("ROLLOUT", Map.of())),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of(), "AST_BOOLEAN");
        index.update("t1", "scene", "*", List.of(snap));

        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm,
                Map.of("ROLLOUT", blockingGate("ROLLOUT")),
                Map.of("AST_BOOLEAN", hitExecutor()));
        assertFalse(engine.evaluate(event("t1", "scene", "EVT")).ruleHit());
    }

    @Test
    void evaluate_directCandidates_skipsIndexLookup() {
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(new SceneRuleIndex(), asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()));

        EvalResult result = engine.evaluate(event("t1", "scene", "ORDER"),
                List.of(snapshot(2L, "t1", "scene")));
        assertTrue(result.ruleHit());
    }

    @Test
    void evaluate_matchWithHit_categoryAndDecisionAreNull() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "scene", "*", List.of(snapshot(1L, "t1", "scene")));

        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()));

        EvalResult result = engine.evaluate(event("t1", "scene", "ORDER"));
        assertNull(result.category());
        assertNull(result.decision());
    }

    @Test
    void evaluate_withExternalNow_propagatesToContext() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "scene", "*", List.of(snapshot(1L, "t1", "scene")));

        Instant fixedNow = Instant.parse("2026-06-01T00:00:00Z");
        // executor 验证 ctx.now() 等于注入的 fixedNow
        RuleVersionExecutor checkingExec = (snap, ctx) -> {
            assertEquals(fixedNow, ctx.now());
            return new EvalResult(true,
                    new Decision("BLOCK", "", 10, snap.ruleVersionId()),
                    List.of(new Decision("BLOCK", "", 10, snap.ruleVersionId())),
                    List.of(), null, List.of(), null, null, null);
        };
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm, Map.of(),
                Map.of("AST_BOOLEAN", checkingExec));

        EvalResult result = engine.evaluate(event("t1", "scene", "ORDER"), fixedNow);
        assertTrue(result.ruleHit());
    }

    @Test
    void evaluate_multipleSnapshots_highestPriorityWins() {
        SceneRuleIndex index = new SceneRuleIndex();
        RuleVersionSnapshot low  = new RuleVersionSnapshot(1L, "scene", "t1",
                EMPTY_AND, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("LOW_RISK", 5)),
                List.of(), "AST_BOOLEAN");
        RuleVersionSnapshot high = new RuleVersionSnapshot(2L, "scene", "t1",
                EMPTY_AND, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 20)),
                List.of(), "AST_BOOLEAN");
        index.update("t1", "scene", "*", List.of(low, high));

        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        // executor 按 decisionBindings 第一条决策，真实地返回命中
        RuleVersionExecutor exec = (snap, ctx) -> new EvalResult(true,
                new Decision(snap.decisionBindings().get(0).decisionCode(), "",
                        snap.decisionBindings().get(0).priority(), snap.ruleVersionId()),
                List.of(new Decision(snap.decisionBindings().get(0).decisionCode(), "",
                        snap.decisionBindings().get(0).priority(), snap.ruleVersionId())),
                List.of(), null, List.of(), null, null, null);
        EvalEngine engine = new EvalEngine(index, asm, Map.of(), Map.of("AST_BOOLEAN", exec));

        EvalResult result = engine.evaluate(event("t1", "scene", "ORDER"));
        assertTrue(result.ruleHit());
        assertEquals("REJECT", result.finalDecision().code());
    }
}
