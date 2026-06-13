package com.sstlfsj.rule.kernel.api.spi.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

/** 对单个 ConditionNode 在当前执行上下文中进行条件求值。 */
public interface ConditionEvaluator {
    /**
     * 在当前执行上下文中对单个条件节点求值。
     *
     * @param node 待求值的条件节点
     * @param ctx  当前评估上下文
     * @return 条件是否满足
     */
    boolean evaluate(ConditionNode node, EvalContext ctx);
}
