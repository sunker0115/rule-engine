package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleVersionSnapshotTest {

    private static ConditionNode leaf() {
        return new ConditionNode("AMOUNT_GT", "balance", null, Map.of("threshold", 100));
    }

    @Test
    void nullLists_defaultToEmpty() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene1", "t1", leaf(), null, null);
        assertNotNull(snap.preGates());
        assertTrue(snap.preGates().isEmpty());
        assertNotNull(snap.decisionBindings());
        assertTrue(snap.decisionBindings().isEmpty());
    }

    @Test
    void preGates_areImmutable() {
        RuleVersionSnapshot.PreGateConfig gate =
                new RuleVersionSnapshot.PreGateConfig("RATE_LIMIT", Map.of("limit", 10));
        List<RuleVersionSnapshot.PreGateConfig> mutable = new ArrayList<>(List.of(gate));
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "s1", "t1", leaf(), mutable, null);
        mutable.add(gate);
        assertEquals(1, snap.preGates().size(), "构造后修改原始列表不应影响 preGates");
    }

    @Test
    void decisionBindings_areImmutable() {
        RuleVersionSnapshot.DecisionBinding binding =
                new RuleVersionSnapshot.DecisionBinding("BLOCK", 100);
        List<RuleVersionSnapshot.DecisionBinding> mutable = new ArrayList<>(List.of(binding));
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "s1", "t1", leaf(), null, mutable);
        mutable.add(binding);
        assertEquals(1, snap.decisionBindings().size(), "构造后修改原始列表不应影响 decisionBindings");
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
}
