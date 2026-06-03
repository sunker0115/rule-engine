package com.sstlfsj.rule.kernel.api.spi.executor;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

/** 将规则版本快照在给定上下文中求值的 SPI 接口。 */
public interface RuleVersionExecutor {
    /**
     * 对给定规则版本快照和上下文进行求值，返回评估结果。
     *
     * @param snapshot 待评估的规则版本快照
     * @param ctx      当前执行上下文
     * @return 评估结果
     */
    EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx);
}
