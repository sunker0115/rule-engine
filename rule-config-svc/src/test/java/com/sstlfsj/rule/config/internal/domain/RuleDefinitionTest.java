package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies RuleDefinition getter/setter round-trips. */
class RuleDefinitionTest {

    @Test
    void getterSetter_roundTrip() {
        RuleDefinition def = new RuleDefinition();
        def.setId(10L);
        def.setTenantId("tenant1");
        def.setSceneCode("SCENE_A");
        def.setName("规则1");
        def.setStatus("ACTIVE");
        def.setCurrentVersionId(99L);

        assertEquals(10L, def.getId());
        assertEquals("tenant1", def.getTenantId());
        assertEquals("SCENE_A", def.getSceneCode());
        assertEquals("规则1", def.getName());
        assertEquals("ACTIVE", def.getStatus());
        assertEquals(99L, def.getCurrentVersionId());
    }

    @Test
    void defaultValues_areNull() {
        RuleDefinition def = new RuleDefinition();
        assertNull(def.getId());
        assertNull(def.getCurrentVersionId());
    }
}
