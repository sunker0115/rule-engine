package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionContextTest {

    private static EvalContext evalContext() {
        RuleEvent event = new RuleEvent("t1", "s1", "T", "u1", "e1", Instant.EPOCH, Map.of(), null);
        Subject subject = new Subject("u1", SubjectType.USER, Map.of());
        return new EvalContext("t1", event, subject, Map.of());
    }

    @Test
    void params_areImmutable() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        ActionContext ctx = new ActionContext("a1", "SEND_MSG", mutable, evalContext(), 1L, "BLOCK");
        mutable.put("extra", "x");
        assertEquals(1, ctx.params().size(), "构造后修改原始 map 不应影响 ActionContext");
    }

    @Test
    void params_mapIsUnmodifiable() {
        ActionContext ctx = new ActionContext("a1", "SEND_MSG", Map.of(), evalContext(), 1L, "BLOCK");
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.params().put("k", "v"));
    }

    @Test
    void fields_areRetained() {
        EvalContext ec = evalContext();
        ActionContext ctx = new ActionContext("a1", "SEND_MSG", Map.of("k", "v"), ec, 99L, "REVIEW");
        assertEquals("a1", ctx.actionId());
        assertEquals("SEND_MSG", ctx.actionType());
        assertSame(ec, ctx.evalContext());
        assertEquals(99L, ctx.actionExecutionId());
        assertEquals("REVIEW", ctx.decisionCode());
    }
}
