package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.springframework.stereotype.Component;

/** 阻断交易 ActionHandler，v1 stub 实现，直接返回 success。 */
@Component
@ActionType("BLOCK_TRANSACTION")
public class BlockTransactionHandler implements ActionHandler {

    /**
     * 执行阻断交易动作（v1 stub，直接返回成功）。
     *
     * @param ctx 动作执行上下文
     * @return 执行结果
     */
    @Override
    public ActionResult execute(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }
}
