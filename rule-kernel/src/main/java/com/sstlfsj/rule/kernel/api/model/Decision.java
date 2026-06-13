package com.sstlfsj.rule.kernel.api.model;

/** 规则命中后的决策描述，priority 越大越优先。category 为 DECISION_TREE 命中叶子的分类标签，其他 kind 为 null。 */
public record Decision(
        String code,
        String name,
        int priority,
        Long fromRuleVersionId,
        /** 命中规则的逻辑编码;执行器构造时从 snapshot.code() 填充。 */
        String fromRuleCode,
        /** 命中规则的版本号;执行器从 snapshot.version() 填充。 */
        long fromRuleVersion,
        String category
) {
    /** 带 category 的便捷构造。 */
    public Decision(String code, String name, int priority, Long fromRuleVersionId, String category) {
        this(code, name, priority, fromRuleVersionId, null, 0L, category);
    }

    /** 无分类的便捷构造（category=null）。 */
    public Decision(String code, String name, int priority, Long fromRuleVersionId) {
        this(code, name, priority, fromRuleVersionId, null, 0L, null);
    }
}
