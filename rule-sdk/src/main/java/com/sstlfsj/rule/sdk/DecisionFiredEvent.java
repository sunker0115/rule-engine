package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/**
 * 一个决策命中的事件,携带评估事件与上下文(供动作侧取 payload/metric/元数据)。
 * <p>{@code fromRuleCode}/{@code fromRuleVersion} 标识产出该决策的规则 —— 多条规则绑定同一
 * decisionCode 时,动作侧会按命中规则数收到多个事件,凭此区分来源(boolean/scorecard 规则由
 * 执行器从 snapshot 填充)。
 */
public record DecisionFiredEvent(String decisionCode, int priority, String category,
                                 String fromRuleCode, long fromRuleVersion,
                                 RuleEvent event, EvalContext context) {
    /** 决策码是否等于 code。 */
    public boolean decision(String code) { return decisionCode.equals(code); }
}
