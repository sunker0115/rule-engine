package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.springframework.stereotype.Component;

/** 发送告警 ActionHandler，v1 stub 实现，execute 和 dryRun 均直接返回 success。 */
@Component
@ActionType("SEND_ALERT")
public class SendAlertHandler implements ActionHandler {

    /**
     * 执行发送告警动作（v1 stub，直接返回成功）。
     *
     * @param ctx 动作执行上下文
     * @return 执行结果
     */
    @Override
    public ActionResult execute(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }

    /**
     * dry-run 预览发送告警动作（v1 stub，返回与 execute 相同的成功结果）。
     *
     * @param ctx 动作执行上下文
     * @return 预览结果，status=SUCCESS
     */
    @Override
    public ActionResult dryRun(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }
}
