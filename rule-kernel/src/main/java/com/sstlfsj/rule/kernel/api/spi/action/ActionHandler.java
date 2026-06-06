package com.sstlfsj.rule.kernel.api.spi.action;

import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;

/** 规则命中后执行对应动作的 SPI 接口。 */
public interface ActionHandler {
    /**
     * 执行规则命中后触发的业务动作。
     *
     * @param ctx 动作执行上下文（含命中规则、决策、主体等信息）
     * @return 动作执行结果
     */
    ActionResult execute(ActionContext ctx);

    /**
     * 补偿（回滚）已执行的动作；默认实现不支持补偿。
     *
     * @param ctx 动作执行上下文
     * @return 补偿结果，默认返回 notSupported
     */
    default ActionResult compensate(ActionContext ctx) {
        return ActionResult.notSupported();
    }

    /**
     * 试运行动作（dry-run），不产生真实副作用；默认实现跳过。
     *
     * @param ctx 动作执行上下文
     * @return 试运行结果，默认返回标记 DRY_RUN_NOT_IMPLEMENTED 的跳过结果
     */
    default ActionResult dryRun(ActionContext ctx) {
        return ActionResult.skipped(ctx.actionId(), ctx.actionType(), "DRY_RUN_NOT_IMPLEMENTED");
    }
}
