package com.sstlfsj.rule.kernel.api.model;

/** 规则命中后的决策描述，priority 越大越优先。category 为 DECISION_TREE 命中叶子的分类标签，其他 kind 为 null。 */
public record Decision(
        String code,
        String name,
        int priority,
        Long fromRuleVersionId,
        String category
) {
    /** 无分类（boolean/scorecard/decision-table 等）的便捷构造，category=null。 */
    public Decision(String code, String name, int priority, Long fromRuleVersionId) {
        this(code, name, priority, fromRuleVersionId, null);
    }
}
