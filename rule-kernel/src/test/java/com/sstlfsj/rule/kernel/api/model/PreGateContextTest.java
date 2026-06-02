package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreGateContextTest {

    private static RuleEvent event() {
        return new RuleEvent("t1", "s1", "LOGIN", "u1", "e1", Instant.EPOCH, Map.of(), null);
    }

    @Test
    void fields_areRetained() {
        RuleEvent ev = event();
        PreGateContext ctx = new PreGateContext("t1", "scene1", "u1", ev);
        assertEquals("t1", ctx.tenantId());
        assertEquals("scene1", ctx.sceneCode());
        assertEquals("u1", ctx.subjectId());
        assertSame(ev, ctx.event());
    }

    @Test
    void recordEquality_byValue() {
        RuleEvent ev = event();
        PreGateContext a = new PreGateContext("t1", "s1", "u1", ev);
        PreGateContext b = new PreGateContext("t1", "s1", "u1", ev);
        assertEquals(a, b);
    }
}
