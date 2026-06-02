package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 SceneDef Lombok getter/setter 及字段覆盖。 */
class SceneDefTest {

    @Test
    void getterSetter_roundTrip() {
        SceneDef scene = new SceneDef();
        scene.setId(1L);
        scene.setTenantId(100L);
        scene.setCode("SCENE_A");
        scene.setName("场景A");
        scene.setDescription("测试描述");
        scene.setDominantMode("PUSH");
        scene.setDecisionStrategy("HIGHEST_PRIORITY");
        scene.setSubjectType("USER");
        scene.setEventTypes("[\"payment.initiated\"]");
        scene.setPayloadSchema("{\"amount\":\"LONG\"}");
        scene.setDefaultParams("{\"timezone\":\"Asia/Shanghai\"}");
        scene.setStatus("ACTIVE");
        scene.setCreatedBy("operator1");
        scene.setUpdatedBy("operator2");

        assertEquals(1L, scene.getId());
        assertEquals(100L, scene.getTenantId());
        assertEquals("SCENE_A", scene.getCode());
        assertEquals("场景A", scene.getName());
        assertEquals("测试描述", scene.getDescription());
        assertEquals("PUSH", scene.getDominantMode());
        assertEquals("HIGHEST_PRIORITY", scene.getDecisionStrategy());
        assertEquals("USER", scene.getSubjectType());
        assertEquals("[\"payment.initiated\"]", scene.getEventTypes());
        assertEquals("{\"amount\":\"LONG\"}", scene.getPayloadSchema());
        assertEquals("{\"timezone\":\"Asia/Shanghai\"}", scene.getDefaultParams());
        assertEquals("ACTIVE", scene.getStatus());
        assertEquals("operator1", scene.getCreatedBy());
        assertEquals("operator2", scene.getUpdatedBy());
    }

    @Test
    void defaultValues_areNull() {
        SceneDef scene = new SceneDef();
        assertNull(scene.getId());
        assertNull(scene.getTenantId());
        assertNull(scene.getCode());
        assertNull(scene.getCreatedAt());
        assertNull(scene.getUpdatedAt());
    }
}
