package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

// 策略分支（switch: FIRST_HIT / HIGHEST_PRIORITY / ALL_HITS）测试见 EvalEngineStrategyTest

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalEngineTest {

    private static final AndNode EMPTY_AND = new AndNode(List.of(), null, null);

    private static RuleEvent event(String tenantId, String sceneCode, String eventType) {
        return new RuleEvent(tenantId, sceneCode, eventType, "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
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
                Map.of("AST_BOOLEAN", hitExecutor()), true);
        assertFalse(engine.evaluate(event("t1", "scene", "ORDER")).ruleHit());
    }

    @Test
    void evaluate_matchWithHit_returnsDecision() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "scene", "*", List.of(snapshot(1L, "t1", "scene")));

        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()), true);

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
                Map.of("AST_BOOLEAN", missExecutor()), true);
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
                Map.of("AST_BOOLEAN", hitExecutor()), true);
        assertFalse(engine.evaluate(event("t1", "scene", "EVT")).ruleHit());
    }

    @Test
    void finalDecision_carriesNameAndActionsFromBinding() {
        // boolean 规则命中 → resolveRuleDecisions 回退路径从 binding 取 name/actions(修 name 永远空串 bug)
        RuleVersionSnapshot.DecisionAction action =
                new RuleVersionSnapshot.DecisionAction("a1", "SEND_ALERT", 0, Map.of());
        RuleVersionSnapshot.DecisionBinding binding =
                new RuleVersionSnapshot.DecisionBinding("REJECT", "拒绝", 10, List.of(action));
        SceneRuleIndex index = new SceneRuleIndex();
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1",
                EMPTY_AND, List.of(), List.of(binding), List.of(), "AST_BOOLEAN");
        index.update("t1", "scene", "*", List.of(snap));

        // AST_BOOLEAN 真实语义:命中但 hitDecisions 空 → resolveRuleDecisions 走 binding 回退赋决策
        RuleVersionExecutor boolHit = (s, c) ->
                new EvalResult(true, null, List.of(), List.of(), null, List.of(), null, null, null);
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm, Map.of(),
                Map.of("AST_BOOLEAN", boolHit), true);
        EvalResult r = engine.evaluate(event("t1", "scene", "EVT"));

        assertEquals("拒绝", r.finalDecision().name());          // 不再是空串
        assertEquals(List.of(action), r.finalDecision().actions());   // actions 透传供派发
    }

    @Test
    void evaluate_unregisteredPreGate_failClosed_returnsMiss() {
        // 配了一个未注册的 gateType(注册表为空),fail-closed:视为拦截而非静默放行
        SceneRuleIndex index = new SceneRuleIndex();
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1",
                EMPTY_AND,
                List.of(new RuleVersionSnapshot.PreGateConfig("UNKNOWN_GATE", Map.of())),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of(), "AST_BOOLEAN");
        index.update("t1", "scene", "*", List.of(snap));

        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm,
                Map.of(),   // 空注册表:UNKNOWN_GATE 无对应 PreGate 实现
                Map.of("AST_BOOLEAN", hitExecutor()), true);
        assertFalse(engine.evaluate(event("t1", "scene", "EVT")).ruleHit());
    }

    @Test
    void evaluateWithContext_directCandidates_skipsIndexLookup() {
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(new SceneRuleIndex(), asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()), true);

        EvalOutcome outcome = engine.evaluateWithContext(event("t1", "scene", "ORDER"),
                List.of(snapshot(2L, "t1", "scene")),
                SceneExecutionStrategy.HIGHEST_PRIORITY, Instant.now());
        assertTrue(outcome.result().ruleHit());
        assertNotNull(outcome.context());
    }

    @Test
    void match_returnsIndexedCandidates() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "scene", "*", List.of(snapshot(1L, "t1", "scene")));
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()), true);

        assertEquals(1, engine.match(event("t1", "scene", "ORDER")).size());
    }

    @Test
    void evaluateWithContext_hit_contextNonNull() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "scene", "*", List.of(snapshot(1L, "t1", "scene")));
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()), true);

        RuleEvent ev = event("t1", "scene", "ORDER");
        EvalOutcome outcome = engine.evaluateWithContext(ev, engine.match(ev), Instant.now());
        assertTrue(outcome.result().ruleHit());
        assertNotNull(outcome.context());
    }

    @Test
    void evaluateWithContext_emptyCandidates_missWithNullContext() {
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(new SceneRuleIndex(), asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()), true);

        EvalOutcome outcome = engine.evaluateWithContext(event("t1", "scene", "ORDER"),
                List.of(), SceneExecutionStrategy.HIGHEST_PRIORITY, Instant.now());
        assertFalse(outcome.result().ruleHit());
        assertNull(outcome.context());
    }

    @Test
    void evaluateWithContext_allPreGatesBlock_missWithNullContext() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1",
                EMPTY_AND,
                List.of(new RuleVersionSnapshot.PreGateConfig("ROLLOUT", Map.of())),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of(), "AST_BOOLEAN");
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(new SceneRuleIndex(), asm,
                Map.of("ROLLOUT", blockingGate("ROLLOUT")),
                Map.of("AST_BOOLEAN", hitExecutor()), true);

        EvalOutcome outcome = engine.evaluateWithContext(event("t1", "scene", "EVT"),
                List.of(snap), SceneExecutionStrategy.HIGHEST_PRIORITY, Instant.now());
        assertFalse(outcome.result().ruleHit());
        assertNull(outcome.context());
    }

    @Test
    void evaluate_matchWithHit_categoryAndDecisionAreNull() {
        SceneRuleIndex index = new SceneRuleIndex();
        index.update("t1", "scene", "*", List.of(snapshot(1L, "t1", "scene")));

        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        EvalEngine engine = new EvalEngine(index, asm, Map.of(),
                Map.of("AST_BOOLEAN", hitExecutor()), true);

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
                Map.of("AST_BOOLEAN", checkingExec), true);

        RuleEvent ev = event("t1", "scene", "ORDER");
        EvalResult result = engine.evaluateWithContext(ev, engine.match(ev), fixedNow).result();
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
        EvalEngine engine = new EvalEngine(index, asm, Map.of(), Map.of("AST_BOOLEAN", exec), true);

        EvalResult result = engine.evaluate(event("t1", "scene", "ORDER"));
        assertTrue(result.ruleHit());
        assertEquals("REJECT", result.finalDecision().code());
    }

    private static final String ALWAYS_TRUE = "ALWAYS_TRUE";
    // 恒真条件：无指标依赖，命中布尔在收集/不收集 trace 两种模式下完全一致
    private static final AstNode ALWAYS_TRUE_AST =
            new ConditionNode(ALWAYS_TRUE, null, null, Map.of(), 0.0);

    /** 用真实 InterpretedExecutor（读 TraceScope.COLLECT）+ 恒真算子构建引擎，以验证 collectTrace 开关。 */
    private static EvalEngine interpretedEngine() {
        Map<String, ConditionEvaluator> evaluators = new HashMap<>(KernelEvaluators.defaults());
        evaluators.put(ALWAYS_TRUE, (node, ctx) -> true);
        return new EvalEngine(new SceneRuleIndex(),
                new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", new InterpretedExecutor(evaluators)), true);
    }

    @Test
    void collectTraceParam_togglesNodeTrace_butKeepsSameRuleHit() {
        EvalEngine engine = interpretedEngine();
        RuleEvent ev = event("t1", "scene", "ORDER");
        List<RuleVersionSnapshot> candidates = List.of(new RuleVersionSnapshot(
                1L, "scene", "t1", ALWAYS_TRUE_AST, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of(), "AST_BOOLEAN"));
        Instant now = Instant.now();

        EvalOutcome off = engine.evaluateWithContext(ev, candidates,
                SceneExecutionStrategy.HIGHEST_PRIORITY, now, false);
        EvalOutcome on = engine.evaluateWithContext(ev, candidates,
                SceneExecutionStrategy.HIGHEST_PRIORITY, now, true);

        assertTrue(off.result().nodeTrace().isEmpty());
        assertFalse(on.result().nodeTrace().isEmpty());
        assertEquals(on.result().ruleHit(), off.result().ruleHit());
    }
}
