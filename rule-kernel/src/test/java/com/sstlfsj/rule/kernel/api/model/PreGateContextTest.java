package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreGateContextTest {

    @Test
    void constructor_setsAllFields() {
        RuleEvent event = new RuleEvent("t1", "scene1", "EVENT_A", "u1",
                "eid1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        Instant now = Instant.now();
        PreGateContext ctx = new PreGateContext(
                "t1", "scene1", "u1", event, 42L,
                Map.of("percentage", 50), now);

        assertEquals("t1", ctx.tenantId());
        assertEquals("scene1", ctx.sceneCode());
        assertEquals("u1", ctx.subjectId());
        assertEquals(event, ctx.event());
        assertEquals(42L, ctx.ruleVersionId());
        assertEquals(50, ctx.gateParams().get("percentage"));
        assertEquals(now, ctx.occurredAt());
    }

    @Test
    void nullGateParams_defaultsToEmptyMap() {
        RuleEvent event = new RuleEvent("t1", "scene1", "EVENT_A", "u1",
                "eid1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        PreGateContext ctx = new PreGateContext("t1", "scene1", "u1", event, 1L, null, Instant.now());

        assertNotNull(ctx.gateParams());
        assertTrue(ctx.gateParams().isEmpty());
    }

    @Test
    void gateParams_isImmutable() {
        RuleEvent event = new RuleEvent("t1", "scene1", "EVENT_A", "u1",
                "eid1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        PreGateContext ctx = new PreGateContext("t1", "scene1", "u1", event, 1L,
                Map.of("percentage", 30), Instant.now());

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.gateParams().put("k", "v"));
    }
}
