package com.sstlfsj.rule.config.api.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 MetricWriteService 内嵌 record MetricWriteCommand 可正常构造，accessor 返回预期值。 */
class MetricWriteServiceTest {

    @Test
    void metricWriteCommand_recordAccessors() {
        var cmd = new MetricWriteService.MetricWriteCommand(
                "用户年龄", "ATTRIBUTE", "LONG", Map.of("window", "30d"), 120, true);

        assertEquals("用户年龄", cmd.name());
        assertEquals("ATTRIBUTE", cmd.sourceType());
        assertEquals("LONG", cmd.dataType());
        assertEquals(Map.of("window", "30d"), cmd.params());
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
    void metricWriteCommand_nullParams_isAllowed() {
        var cmd = new MetricWriteService.MetricWriteCommand("x", "ATTRIBUTE", "LONG", null, null, false);
        assertNull(cmd.params());
        assertNull(cmd.cacheTtlSeconds());
    }

    @Test
    void metricWriteCommand_sensitiveAccessor() {
        var cmd = new MetricWriteService.MetricWriteCommand(
                "身份证号", "ATTRIBUTE", "STRING", Map.of(), 60, false, true);
        assertTrue(cmd.sensitive());
    }

    @Test
    void metricWriteCommand_legacyConstructor_defaultsSensitiveFalse() {
        var cmd = new MetricWriteService.MetricWriteCommand("x", "ATTRIBUTE", "LONG", null, 60, false);
        assertFalse(cmd.sensitive());
    }

    @Test
    void metricWriteCommand_deserializesMissingSensitiveAsFalse() throws Exception {
        // 请求体缺 sensitive 键时不得因 Jackson 3 FAIL_ON_NULL_FOR_PRIMITIVES 报错，须落 false
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var cmd = mapper.readValue(
                "{\"name\":\"账龄\",\"sourceType\":\"ATTRIBUTE\",\"dataType\":\"LONG\","
                        + "\"params\":{},\"cacheTtlSeconds\":60,\"allowProvided\":false}",
                MetricWriteService.MetricWriteCommand.class);
        assertFalse(cmd.sensitive());
    }

    @Test
    void metricWriteCommand_deserializesSensitiveTrue() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var cmd = mapper.readValue(
                "{\"name\":\"身份证号\",\"sourceType\":\"ATTRIBUTE\",\"dataType\":\"STRING\","
                        + "\"allowProvided\":false,\"sensitive\":true}",
                MetricWriteService.MetricWriteCommand.class);
        assertTrue(cmd.sensitive());
    }

    // ── RuleRef ───────────────────────────────────────────────────────────────

    @Test
    void ruleRef_recordAccessors() {
        // 新字段：ruleDefinitionId, ruleCode, ruleName, sceneCode, status（去掉 ruleVersionId）
        var ref = new MetricWriteService.RuleRef(10L, "risk.transfer", "转账风控",
                "risk.transfer", "ACTIVE");

        assertEquals(10L, ref.ruleDefinitionId());
        assertEquals("risk.transfer", ref.ruleCode());
        assertEquals("转账风控", ref.ruleName());
        assertEquals("risk.transfer", ref.sceneCode());
        assertEquals("ACTIVE", ref.status());
    }

    @Test
    void ruleRef_recordEquality() {
        var a = new MetricWriteService.RuleRef(10L, "risk.transfer", "转账风控",
                "risk.transfer", "ACTIVE");
        var b = new MetricWriteService.RuleRef(10L, "risk.transfer", "转账风控",
                "risk.transfer", "ACTIVE");
        assertEquals(a, b);
    }
}
