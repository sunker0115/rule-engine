package com.sstlfsj.rule.kernel.internal.codec;

import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotAssemblerTest {

    private final AstJsonCodec codec = new AstJsonCodec();
    private final SnapshotAssembler assembler = new SnapshotAssembler();

    private RuleVersionRow row(Long id, String scene, Long tenantId,
                               String astJson, String preGates,
                               String decisions, String eventTypes, String kind) {
        return new RuleVersionRow(id, scene, tenantId, astJson, preGates, decisions, eventTypes,
                kind, "HIGHEST_PRIORITY");
    }

    @Test
    void assemble_producesCorrectSnapshot() throws Exception {
        RuleVersionRow r = row(42L, "fraud", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"GT\",\"metricCode\":\"score\",\"params\":{\"threshold\":80}}",
                "[]", "[{\"decisionCode\":\"REJECT\",\"priority\":10}]", "[\"RISK_EVENT\"]", "AST_BOOLEAN");
        RuleVersionSnapshot snap = assembler.assemble(r);
        assertEquals(42L, snap.ruleVersionId());
        assertEquals("fraud", snap.sceneCode());
        assertEquals("1", snap.tenantId());
        assertInstanceOf(ConditionNode.class, snap.conditionAst());
        assertEquals(1, snap.decisionBindings().size());
        assertEquals("REJECT", snap.decisionBindings().get(0).decisionCode());
        assertEquals(List.of("RISK_EVENT"), snap.triggerEventTypes());
        assertEquals("AST_BOOLEAN", snap.kind());
    }

    @Test
    void assemble_kindNull_defaultsToAstBoolean() throws Exception {
        RuleVersionRow r = row(1L, "s", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[]", "[]", "[]", null);
        assertEquals("AST_BOOLEAN", assembler.assemble(r).kind());
    }

    @Test
    void assembleAll_skipsInvalidJson() {
        // 同时覆盖 assembleAll 中 log.warn 的代码路径（System.err.println 已改为 slf4j）
        RuleVersionRow bad  = row(99L, "bad", 1L, "NOT_JSON", "[]", "[]", "[]", "AST_BOOLEAN");
        RuleVersionRow good = row(1L,  "ok",  1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[]", "[]", "[]", "AST_BOOLEAN");
        List<RuleVersionSnapshot> results = assembler.assembleAll(List.of(bad, good));
        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).ruleVersionId());
    }

    @Test
    void assembleAll_emptyInput_returnsEmptyList() {
        assertTrue(assembler.assembleAll(List.of()).isEmpty());
    }

    @Test
    void assemble_populatesMetricDependencies() throws Exception {
        RuleVersionRow r = new RuleVersionRow(1L, "PAY", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"GT\",\"metricCode\":\"score\",\"params\":{\"threshold\":1}}",
                "[]", "[]", "[]", "AST_BOOLEAN", "HIGHEST_PRIORITY",
                "[{\"metricCode\":\"m1\",\"metricVersion\":1}]", "[]");
        RuleVersionSnapshot snap = assembler.assemble(r);
        assertEquals(List.of(new MetricDependency("m1", 1)), snap.metricDependencies());
    }

    @Test
    void assemble_nullMetricDependenciesJson_yieldsEmptyList() throws Exception {
        RuleVersionRow r = row(1L, "PAY", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[]", "[]", "[]", "AST_BOOLEAN");
        RuleVersionSnapshot snap = assembler.assemble(r);
        assertTrue(snap.metricDependencies().isEmpty());
    }

    @Test
    void assemble_populatesPayloadDependencies() throws Exception {
        RuleVersionRow r = new RuleVersionRow(1L, "PAY", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"GT\",\"metricCode\":\"amount\",\"params\":{\"threshold\":1}}",
                "[]", "[]", "[]", "AST_BOOLEAN", "HIGHEST_PRIORITY",
                "[]",
                "[{\"name\":\"amount\",\"dataType\":\"DECIMAL\",\"required\":true}]");
        RuleVersionSnapshot snap = assembler.assemble(r);
        org.assertj.core.api.Assertions.assertThat(snap.payloadDependencies())
                .containsExactly(new com.sstlfsj.rule.kernel.api.model.PayloadDependency("amount", "DECIMAL", true));
    }

    @Test
    void assemble_nullPayloadDependenciesJson_yieldsEmptyList() throws Exception {
        RuleVersionRow r = row(1L, "PAY", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[]", "[]", "[]", "AST_BOOLEAN");
        RuleVersionSnapshot snap = assembler.assemble(r);
        org.assertj.core.api.Assertions.assertThat(snap.payloadDependencies()).isEmpty();
    }
}
