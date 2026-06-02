package com.sstlfsj.rule.kernel.api.spi.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

/** Evaluates a single ConditionNode against the current execution context. */
public interface ConditionEvaluator {
    boolean evaluate(ConditionNode node, EvalContext ctx);
}
