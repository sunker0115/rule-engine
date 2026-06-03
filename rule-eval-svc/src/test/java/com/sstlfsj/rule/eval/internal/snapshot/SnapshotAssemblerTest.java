package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotAssemblerTest {

    private final AstJsonCodec codec = new AstJsonCodec();
    private final SnapshotAssembler assembler = new SnapshotAssembler(codec);

    @Test
    void assemble_producesCorrectSnapshot() throws Exception {
        RuleVersionRow row = new RuleVersionRow(
                42L,
                "fraud_check",
                1L,
                """
                {"type":"ConditionNode","conditionType":"GT",
                 "metricCode":"score","params":{"threshold":80}}
                """,
                "[]",
                "[{\"decisionCode\":\"REJECT\",\"priority\":10}]",
                "[\"RISK_EVENT\"]"
        );

        RuleVersionSnapshot snapshot = assembler.assemble(row);

        assertEquals(42L, snapshot.ruleVersionId());
        assertEquals("fraud_check", snapshot.sceneCode());
        assertEquals("1", snapshot.tenantId());
        assertInstanceOf(ConditionNode.class, snapshot.conditionAst());
        assertEquals(1, snapshot.decisionBindings().size());
        assertEquals("REJECT", snapshot.decisionBindings().get(0).decisionCode());
        assertTrue(snapshot.preGates().isEmpty());
        assertEquals(List.of("RISK_EVENT"), snapshot.triggerEventTypes());
    }

    @Test
    void assemble_withRolloutGate() throws Exception {
        RuleVersionRow row = new RuleVersionRow(
                1L, "scene1", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[{\"gateType\":\"ROLLOUT\",\"params\":{\"percentage\":10}}]",
                "[]",
                "[\"E1\"]"
        );

        RuleVersionSnapshot snapshot = assembler.assemble(row);
        assertEquals(1, snapshot.preGates().size());
        assertEquals("ROLLOUT", snapshot.preGates().get(0).gateType());
    }

    @Test
    void assembleAll_skipsInvalidJson_returnsValidOnes() {
        // 第一行 AST JSON 故意损坏，第二行合法；期望只返回第二行
        RuleVersionRow bad = new RuleVersionRow(
                99L, "scene_bad", 1L,
                "NOT_JSON",
                "[]", "[]", "[]"
        );
        RuleVersionRow good = new RuleVersionRow(
                100L, "scene_good", 2L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[]", "[]", "[]"
        );

        List<RuleVersionSnapshot> results = assembler.assembleAll(List.of(bad, good));

        assertEquals(1, results.size());
        assertEquals(100L, results.get(0).ruleVersionId());
    }

    @Test
    void assembleAll_emptyInput_returnsEmptyList() {
        List<RuleVersionSnapshot> results = assembler.assembleAll(List.of());
        assertTrue(results.isEmpty());
    }

    /** triggerEventTypesJson 为空数组时，快照 triggerEventTypes 为空列表（通配）。 */
    @Test
    void assemble_emptyTriggerEventTypes_defaultsToEmptyList() throws Exception {
        RuleVersionRow row = new RuleVersionRow(
                1L, "scene1", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[]", "[]", "[]"
        );

        RuleVersionSnapshot snapshot = assembler.assemble(row);

        assertNotNull(snapshot.triggerEventTypes());
        assertTrue(snapshot.triggerEventTypes().isEmpty());
    }

    /** triggerEventTypesJson 含多个事件类型时，全部保留。 */
    @Test
    void assemble_multipleTriggerEventTypes_allRetained() throws Exception {
        RuleVersionRow row = new RuleVersionRow(
                2L, "scene1", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[]", "[]", "[\"login\",\"payment\"]"
        );

        RuleVersionSnapshot snapshot = assembler.assemble(row);

        assertEquals(List.of("login", "payment"), snapshot.triggerEventTypes());
    }
}
