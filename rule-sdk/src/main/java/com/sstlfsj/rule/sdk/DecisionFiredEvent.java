package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 一个决策命中的事件,携带评估事件与上下文(供动作侧取 payload/metric/元数据)。 */
public record DecisionFiredEvent(String decisionCode, int priority, String category,
                                 RuleEvent event, EvalContext context) {
    /** 决策码是否等于 code。 */
    public boolean decision(String code) { return decisionCode.equals(code); }
}
