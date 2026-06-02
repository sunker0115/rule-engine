package com.sstlfsj.rule.kernel.api.spi.action;

import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;

/** Executes a rule action after a rule fires. */
public interface ActionHandler {
    ActionResult execute(ActionContext ctx);

    default ActionResult compensate(ActionContext ctx) {
        return ActionResult.notSupported();
    }

    default ActionResult dryRun(ActionContext ctx) {
        return ActionResult.skipped(ctx.actionId(), ctx.actionType(), "DRY_RUN_NOT_IMPLEMENTED");
    }
}
