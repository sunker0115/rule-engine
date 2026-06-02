package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies RuleVersion getter/setter round-trips. */
class RuleVersionTest {

    @Test
    void getterSetter_roundTrip() {
        RuleVersion ver = new RuleVersion();
        ver.setId(5L);
        ver.setTenantId("tenant1");
        ver.setRuleDefinitionId(10L);
        ver.setConditionAstJson("{\"type\":\"GT\"}");
        ver.setPreGatesJson("[]");
        ver.setDecisionBindingsJson("{}");
        ver.setStatus("ACTIVE");

        assertEquals(5L, ver.getId());
        assertEquals("tenant1", ver.getTenantId());
        assertEquals(10L, ver.getRuleDefinitionId());
        assertEquals("{\"type\":\"GT\"}", ver.getConditionAstJson());
        assertEquals("[]", ver.getPreGatesJson());
        assertEquals("{}", ver.getDecisionBindingsJson());
        assertEquals("ACTIVE", ver.getStatus());
    }

    @Test
    void defaultValues_areNull() {
        RuleVersion ver = new RuleVersion();
        assertNull(ver.getId());
        assertNull(ver.getConditionAstJson());
    }
}
