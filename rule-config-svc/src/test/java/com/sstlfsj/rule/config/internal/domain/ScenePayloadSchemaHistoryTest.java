package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 ScenePayloadSchemaHistory Lombok getter/setter 及字段覆盖（D13）。 */
class ScenePayloadSchemaHistoryTest {

    @Test
    void getterSetter_roundTrip() {
        ScenePayloadSchemaHistory history = new ScenePayloadSchemaHistory();
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

        history.setId(1L);
        history.setSceneId(42L);
        history.setVersion(2);
        history.setSchemaJson("[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true}]");
        history.setCreatedBy("operator1");
        history.setCreatedAt(now);

        assertEquals(1L, history.getId());
        assertEquals(42L, history.getSceneId());
        assertEquals(2, history.getVersion());
        assertEquals("[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true}]", history.getSchemaJson());
        assertEquals("operator1", history.getCreatedBy());
        assertEquals(now, history.getCreatedAt());
    }

    @Test
    void defaultValues_areNull() {
        ScenePayloadSchemaHistory history = new ScenePayloadSchemaHistory();
        assertNull(history.getId());
        assertNull(history.getSceneId());
        assertNull(history.getVersion());
        assertNull(history.getSchemaJson());
        assertNull(history.getCreatedBy());
        assertNull(history.getCreatedAt());
    }

    @Test
    void schemaJson_存储原始JSON字符串_调用方负责序列化() {
        // 验证 schemaJson 字段约定：调用方序列化后直接赋值原始字符串，不做二次处理
        String rawJson = "[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true},"
                + "{\"name\":\"currency\",\"type\":\"STRING\",\"required\":true}]";
        ScenePayloadSchemaHistory history = new ScenePayloadSchemaHistory();
        history.setSchemaJson(rawJson);
        // 取出后必须与写入完全一致，不应有任何转义或包装
        assertEquals(rawJson, history.getSchemaJson());
        assertTrue(history.getSchemaJson().startsWith("["), "schemaJson 必须是 JSON 数组字符串");
    }
}
