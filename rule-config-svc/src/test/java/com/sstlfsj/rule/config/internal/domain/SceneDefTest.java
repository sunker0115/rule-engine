package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies SceneDef getter/setter round-trips. */
class SceneDefTest {

    @Test
    void getterSetter_roundTrip() {
        SceneDef scene = new SceneDef();
        scene.setId(1L);
        scene.setTenantId("tenant1");
        scene.setCode("SCENE_A");
        scene.setName("场景A");
        scene.setStatus("ACTIVE");
        scene.setDominantMode("FIRST");
        scene.setSubjectType("USER");

        assertEquals(1L, scene.getId());
        assertEquals("tenant1", scene.getTenantId());
        assertEquals("SCENE_A", scene.getCode());
        assertEquals("场景A", scene.getName());
        assertEquals("ACTIVE", scene.getStatus());
        assertEquals("FIRST", scene.getDominantMode());
        assertEquals("USER", scene.getSubjectType());
    }

    @Test
    void defaultValues_areNull() {
        SceneDef scene = new SceneDef();
        assertNull(scene.getId());
        assertNull(scene.getTenantId());
        assertNull(scene.getCode());
    }
}
