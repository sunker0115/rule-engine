package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 RuleVersion Lombok getter/setter 及字段覆盖。 */
class RuleVersionTest {

    @Test
    void getterSetter_roundTrip() {
        RuleVersion ver = new RuleVersion();
        ver.setId(5L);
        ver.setRuleDefinitionId(10L);
        ver.setVersion(1L);
        ver.setConditionAst("{\"type\":\"ConditionNode\"}");
        ver.setDecisionBindings("[]");
        ver.setPreGates("[]");
        ver.setKind("AST_BOOLEAN");
        ver.setTriggerEventTypes("[\"payment.initiated\"]");
        ver.setMetricDependencies("[\"user.age\"]");
        ver.setStatus("ACTIVE");
        ver.setPublishedBy("operator1");

        assertEquals(5L, ver.getId());
        assertEquals(10L, ver.getRuleDefinitionId());
        assertEquals(1L, ver.getVersion());
        assertEquals("{\"type\":\"ConditionNode\"}", ver.getConditionAst());
        assertEquals("[]", ver.getDecisionBindings());
        assertEquals("[]", ver.getPreGates());
        assertEquals("AST_BOOLEAN", ver.getKind());
        assertEquals("[\"payment.initiated\"]", ver.getTriggerEventTypes());
        assertEquals("[\"user.age\"]", ver.getMetricDependencies());
        assertEquals("ACTIVE", ver.getStatus());
        assertEquals("operator1", ver.getPublishedBy());
    }

    @Test
    void defaultValues_areNull() {
        RuleVersion ver = new RuleVersion();
        assertNull(ver.getId());
        assertNull(ver.getConditionAst());
        assertNull(ver.getPublishedAt());
        assertNull(ver.getCreatedAt());
    }
}
