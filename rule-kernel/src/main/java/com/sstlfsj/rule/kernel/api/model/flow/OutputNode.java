package com.sstlfsj.rule.kernel.api.model.flow;

/**
 * 终点节点：产出 decisionCode 对应的决策，汇入 flow 结果集（进 EvalResult 的 finalDecision/hitDecisions）。
 *
 * @param id           节点在图内的唯一 id
 * @param decisionCode 产出的决策码
 */
public record OutputNode(String id, String decisionCode) implements FlowNode {}
