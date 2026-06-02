package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/** ActionHandler.execute() 的入参，包含 Action 定义参数和本次评估上下文。 */
public record ActionContext(
        String actionId,
        String actionType,
        Map<String, Object> params,
        EvalContext evalContext,
        Long actionExecutionId,
        String decisionCode
) {
    public ActionContext {
        params = Map.copyOf(params);
    }
}
