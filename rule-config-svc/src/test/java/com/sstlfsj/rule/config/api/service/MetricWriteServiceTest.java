package com.sstlfsj.rule.config.api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 MetricWriteService 内嵌 record MetricWriteCommand 可正常构造，accessor 返回预期值。 */
class MetricWriteServiceTest {

    @Test
    void metricWriteCommand_recordAccessors() {
        var cmd = new MetricWriteService.MetricWriteCommand(
                "用户年龄", "ATTRIBUTE", "LONG", "{\"window\":\"30d\"}", 120, true);

        assertEquals("用户年龄", cmd.name());
        assertEquals("ATTRIBUTE", cmd.sourceType());
        assertEquals("LONG", cmd.dataType());
        assertEquals("{\"window\":\"30d\"}", cmd.paramsJson());
        assertEquals(120, cmd.cacheTtlSeconds());
        assertTrue(cmd.allowProvided());
    }

    @Test
    void metricWriteCommand_recordEquality() {
        var a = new MetricWriteService.MetricWriteCommand("名称", "ATTRIBUTE", "LONG", null, 60, false);
        var b = new MetricWriteService.MetricWriteCommand("名称", "ATTRIBUTE", "LONG", null, 60, false);
        assertEquals(a, b);
    }

    @Test
    void metricWriteCommand_nullParamsJson_isAllowed() {
        var cmd = new MetricWriteService.MetricWriteCommand("x", "ATTRIBUTE", "LONG", null, null, false);
        assertNull(cmd.paramsJson());
        assertNull(cmd.cacheTtlSeconds());
    }

    // ── RuleRef ───────────────────────────────────────────────────────────────

    @Test
    void ruleRef_recordAccessors() {
        var ref = new MetricWriteService.RuleRef(10L, "risk.transfer", "转账风控", 200L);

        assertEquals(10L, ref.ruleDefinitionId());
        assertEquals("risk.transfer", ref.ruleCode());
        assertEquals("转账风控", ref.ruleName());
        assertEquals(200L, ref.ruleVersionId());
    }

    @Test
    void ruleRef_recordEquality() {
        var a = new MetricWriteService.RuleRef(10L, "risk.transfer", "转账风控", 200L);
        var b = new MetricWriteService.RuleRef(10L, "risk.transfer", "转账风控", 200L);
        assertEquals(a, b);
    }
}
