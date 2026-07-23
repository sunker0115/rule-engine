package com.sstlfsj.rule.kernel.api.model.flow;

/**
 * 引用一条已有规则作为叶子；被引规则的版本在发布期冻结，不写死在图里。
 *
 * @param id       节点在图内的唯一 id
 * @param ruleCode 被引规则的逻辑编码（tenant 内唯一；v1 限同 Scene）
 */
public record RuleRefNode(String id, String ruleCode) implements FlowNode {}
