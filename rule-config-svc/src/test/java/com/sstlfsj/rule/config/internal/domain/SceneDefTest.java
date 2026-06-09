package com.sstlfsj.rule.config.internal.domain;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        PayloadFieldSpec field = new PayloadFieldSpec("amount", "NUMBER", true, null, null, null, null, null);
        scene.setEventTypes(List.of("payment.initiated"));
        scene.setPayloadSchema(List.of(field));
        scene.setDefaultParams(Map.of("timezone", "Asia/Shanghai"));
        scene.setStatus(SceneStatus.ACTIVE);
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
        assertEquals(List.of("payment.initiated"), scene.getEventTypes());
        assertEquals(List.of(field), scene.getPayloadSchema());
        assertEquals(Map.of("timezone", "Asia/Shanghai"), scene.getDefaultParams());
        assertEquals(SceneStatus.ACTIVE, scene.getStatus());
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

    @Test
    void payloadSchemaVersion_getterSetter() {
        SceneDef scene = new SceneDef();
        // 默认为 null，DB 层由 DEFAULT 1 填充
        assertNull(scene.getPayloadSchemaVersion());
        scene.setPayloadSchemaVersion(3);
        assertEquals(3, scene.getPayloadSchemaVersion());
    }
}
