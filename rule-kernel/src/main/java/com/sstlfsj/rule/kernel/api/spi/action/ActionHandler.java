package com.sstlfsj.rule.kernel.api.spi.action;

import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;

/** 规则命中后执行对应动作的 SPI 接口。 */
public interface ActionHandler {
    ActionResult execute(ActionContext ctx);

    default ActionResult compensate(ActionContext ctx) {
        return ActionResult.notSupported();
    }

    default ActionResult dryRun(ActionContext ctx) {
        return ActionResult.skipped(ctx.actionId(), ctx.actionType(), "DRY_RUN_NOT_IMPLEMENTED");
    }
}
