package com.sstlfsj.rule.config.internal.domain;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 ScenePayloadSchemaHistory Lombok getter/setter 及字段覆盖（D13）。 */
class ScenePayloadSchemaHistoryTest {

    @Test
    void getterSetter_roundTrip() {
        ScenePayloadSchemaHistory history = new ScenePayloadSchemaHistory();
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        List<PayloadFieldSpec> schema = List.of(
                new PayloadFieldSpec("amount", "NUMBER", true, null, null, null, null, null));

        history.setId(1L);
        history.setSceneId(42L);
        history.setVersion(2);
        history.setSchema(schema);
        history.setCreatedBy("operator1");
        history.setCreatedAt(now);

        assertEquals(1L, history.getId());
        assertEquals(42L, history.getSceneId());
        assertEquals(2, history.getVersion());
        assertEquals(schema, history.getSchema());
        assertEquals("operator1", history.getCreatedBy());
        assertEquals(now, history.getCreatedAt());
    }

    @Test
    void defaultValues_areNull() {
        ScenePayloadSchemaHistory history = new ScenePayloadSchemaHistory();
        assertNull(history.getId());
        assertNull(history.getSceneId());
        assertNull(history.getVersion());
        assertNull(history.getSchema());
        assertNull(history.getCreatedBy());
        assertNull(history.getCreatedAt());
    }
}
