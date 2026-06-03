package com.sstlfsj.rule.config.api.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 RulePublishedEvent record 构造与 accessor 的正确性。 */
class RulePublishedEventTest {

    @Test
    void constructor_andAccessors() {
        RulePublishedEvent event = new RulePublishedEvent("tenant1", "SCENE_A", 42L);

        assertEquals("tenant1", event.tenantId());
        assertEquals("SCENE_A", event.sceneCode());
        assertEquals(42L, event.ruleVersionId());
    }

    @Test
    void recordEquality() {
        RulePublishedEvent a = new RulePublishedEvent("t1", "S1", 1L);
        RulePublishedEvent b = new RulePublishedEvent("t1", "S1", 1L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
