package com.sstlfsj.rule.config.internal.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 SceneChangedEvent record 构造与 accessor 的正确性。 */
class SceneChangedEventTest {

    @Test
    void constructor_andAccessors_active() {
        SceneChangedEvent event = new SceneChangedEvent("tenant1", "SCENE_A", true);

        assertEquals("tenant1", event.tenantId());
        assertEquals("SCENE_A", event.sceneCode());
        assertTrue(event.active());
    }

    @Test
    void constructor_andAccessors_inactive() {
        SceneChangedEvent event = new SceneChangedEvent("tenant1", "SCENE_A", false);
        assertFalse(event.active());
    }

    @Test
    void recordEquality() {
        SceneChangedEvent a = new SceneChangedEvent("t1", "S1", true);
        SceneChangedEvent b = new SceneChangedEvent("t1", "S1", true);
        assertEquals(a, b);
    }
}
