package com.sstlfsj.rule.kernel.api.spi.action;

import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionHandlerTest {

    private static ActionContext buildCtx() {
        RuleEvent event = new RuleEvent("t1", "SCENE1", "PAYMENT",
                "u1", "e1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext evalCtx = new EvalContext("t1", event, null, Map.<String, MetricValue>of(), Instant.parse("2026-06-01T00:00:00Z"));
        return new ActionContext("a1", "SEND_MSG", Map.of(), evalCtx, 1L, "BLOCK");
    }

    private static final ActionHandler NO_OP = ctx -> ActionResult.success(ctx.actionId(), ctx.actionType());

    @Test
    void execute_returnsSuccess() {
        ActionResult r = NO_OP.execute(buildCtx());
        assertEquals(ActionResult.ActionStatus.SUCCESS, r.status());
    }

    @Test
    void dryRun_defaultReturnsSkippedWithReason() {
        ActionContext ctx = buildCtx();
        ActionResult r = NO_OP.dryRun(ctx);
        assertEquals(ActionResult.ActionStatus.SKIPPED, r.status());
        assertEquals("DRY_RUN_NOT_IMPLEMENTED", r.errorCode());
        assertEquals(ctx.actionId(), r.actionId());
        assertEquals(ctx.actionType(), r.actionType());
    }
}
