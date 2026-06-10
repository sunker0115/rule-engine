package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleVersionSnapshotTest {

    private static ConditionNode leaf() {
        return new ConditionNode("AMOUNT_GT", "balance", null, Map.of("threshold", 100), 0.0);
    }

    @Test
    void nullLists_defaultToEmpty() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene1", "t1", leaf(), null, null, null, null);
        assertNotNull(snap.preGates());
        assertTrue(snap.preGates().isEmpty());
        assertNotNull(snap.decisionBindings());
        assertTrue(snap.decisionBindings().isEmpty());
        assertNotNull(snap.triggerEventTypes());
        assertTrue(snap.triggerEventTypes().isEmpty());
    }

    @Test
    void kind_null_defaultsToAstBoolean() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "s1", "t1", leaf(), null, null, null, null);
        assertEquals("AST_BOOLEAN", snap.kind());
    }

    @Test
    void kind_scorecard_retained() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "s1", "t1", leaf(), null, null, null, "SCORECARD");
        assertEquals("SCORECARD", snap.kind());
    }

    @Test
    void preGates_areImmutable() {
        RuleVersionSnapshot.PreGateConfig gate =
                new RuleVersionSnapshot.PreGateConfig("RATE_LIMIT", Map.of("limit", 10));
        List<RuleVersionSnapshot.PreGateConfig> mutable = new ArrayList<>(List.of(gate));
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "s1", "t1", leaf(), mutable, null, null, null);
        mutable.add(gate);
        assertEquals(1, snap.preGates().size(), "构造后修改原始列表不应影响 preGates");
    }

    @Test
    void decisionBindings_areImmutable() {
        RuleVersionSnapshot.DecisionBinding binding =
                new RuleVersionSnapshot.DecisionBinding("BLOCK", 100);
        List<RuleVersionSnapshot.DecisionBinding> mutable = new ArrayList<>(List.of(binding));
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "s1", "t1", leaf(), null, mutable, null, null);
        mutable.add(binding);
        assertEquals(1, snap.decisionBindings().size(), "构造后修改原始列表不应影响 decisionBindings");
    }

    @Test
    void triggerEventTypes_areImmutable() {
        List<String> mutable = new ArrayList<>(List.of("login"));
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "s1", "t1", leaf(), null, null, mutable, null);
        mutable.add("payment");
        assertEquals(1, snap.triggerEventTypes().size(), "构造后修改原始列表不应影响 triggerEventTypes");
    }

    @Test
    void triggerEventTypes_withValues_retained() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                1L, "s1", "t1", leaf(), null, null, List.of("login", "payment"), null);
        assertEquals(List.of("login", "payment"), snap.triggerEventTypes());
    }

    @Test
    void preGateConfig_params_areImmutable() {
        Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("limit", 10);
        RuleVersionSnapshot.PreGateConfig gate =
                new RuleVersionSnapshot.PreGateConfig("RATE_LIMIT", mutable);
        mutable.put("extra", "x");
        assertEquals(1, gate.params().size(), "构造后修改原始 map 不应影响 PreGateConfig.params");
    }

    @Test
    void preGateConfig_nullParams_defaultToEmpty() {
        RuleVersionSnapshot.PreGateConfig gate =
                new RuleVersionSnapshot.PreGateConfig("RATE_LIMIT", null);
        assertNotNull(gate.params());
        assertTrue(gate.params().isEmpty());
    }

    @Test
    void decisionBinding_fields_areRetained() {
        RuleVersionSnapshot.DecisionBinding binding =
                new RuleVersionSnapshot.DecisionBinding("REVIEW", 50);
        assertEquals("REVIEW", binding.decisionCode());
        assertEquals(50, binding.priority());
    }

    @Test
    void decisionAction_fields_areRetained() {
        RuleVersionSnapshot.DecisionAction action =
                new RuleVersionSnapshot.DecisionAction("a1", "BLOCK_TRANSACTION", 0, Map.of("k", "v"));
        assertEquals("a1", action.actionId());
        assertEquals("BLOCK_TRANSACTION", action.actionType());
        assertEquals(0, action.sortOrder());
        assertEquals(Map.of("k", "v"), action.params());
    }

    @Test
    void decisionAction_params_areImmutable() {
        Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("limit", 10);
        RuleVersionSnapshot.DecisionAction action =
                new RuleVersionSnapshot.DecisionAction("a1", "BLOCK_TRANSACTION", 0, mutable);
        mutable.put("extra", "x");
        assertEquals(1, action.params().size(), "构造后修改原始 map 不应影响 DecisionAction.params");
    }

    @Test
    void decisionAction_nullParams_defaultToEmpty() {
        RuleVersionSnapshot.DecisionAction action =
                new RuleVersionSnapshot.DecisionAction("a1", "BLOCK_TRANSACTION", 0, null);
        assertNotNull(action.params());
        assertTrue(action.params().isEmpty());
    }

    @Test
    void builder_basicFields_roundtrip() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(99L)
                .tenantId("t1")
                .sceneCode("s1")
                .conditionAst(leaf())
                .addTriggerEventType("PAY")
                .addDecisionBinding("BLOCK", 100)
                .build();

        assertEquals(99L, snap.ruleVersionId());
        assertEquals("t1", snap.tenantId());
        assertEquals("s1", snap.sceneCode());
        assertEquals("AST_BOOLEAN", snap.kind());
        assertEquals(List.of("PAY"), snap.triggerEventTypes());
        assertEquals(1, snap.decisionBindings().size());
        assertEquals("BLOCK", snap.decisionBindings().get(0).decisionCode());
    }

    @Test
    void metricDependencies_defaultEmpty_andBuilderAccumulates() {
        RuleVersionSnapshot legacy = new RuleVersionSnapshot(1L, "s1", "t1", leaf(), null, null, null, null);
        assertNotNull(legacy.metricDependencies());
        assertTrue(legacy.metricDependencies().isEmpty());

        RuleVersionSnapshot built = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("s1").tenantId("t1").conditionAst(leaf())
                .addMetricDependency("balance", 1).addMetricDependency("score", 2).build();
        assertEquals(List.of(new MetricDependency("balance", 1), new MetricDependency("score", 2)),
                built.metricDependencies());
    }

    @Test
    void decisionBinding_enrichedFieldsAndCompatCtor() {
        RuleVersionSnapshot.DecisionAction action =
                new RuleVersionSnapshot.DecisionAction("a1", "SEND_ALERT", 0, Map.of("ch", "sms"));
        RuleVersionSnapshot.DecisionBinding enriched =
                new RuleVersionSnapshot.DecisionBinding("REJECT", "拒绝", 10, List.of(action));
        assertEquals("REJECT", enriched.decisionCode());
        assertEquals("拒绝", enriched.name());
        assertEquals(10, enriched.priority());
        assertEquals(List.of(action), enriched.actions());

        // 兼容旧 (code, priority) 构造：name=null、actions 空
        RuleVersionSnapshot.DecisionBinding compat = new RuleVersionSnapshot.DecisionBinding("PASS", 1);
        assertNull(compat.name());
        assertTrue(compat.actions().isEmpty());
    }
}
