package com.sstlfsj.rule.kernel.api.spi.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

/** 对单个 ConditionNode 在当前执行上下文中进行条件求值。 */
public interface ConditionEvaluator {
    boolean evaluate(ConditionNode node, EvalContext ctx);
}
