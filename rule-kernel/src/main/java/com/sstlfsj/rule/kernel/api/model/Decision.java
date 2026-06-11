package com.sstlfsj.rule.kernel.api.model;

import java.util.List;

/** 规则命中后的决策描述，priority 越大越优先。category 为 DECISION_TREE 命中叶子的分类标签，其他 kind 为 null。
 *  actions 为 D27 决策挂载的动作列表，发布期从 decision_definition 冻结进快照、评估期回填，派发期消费。 */
public record Decision(
        String code,
        String name,
        int priority,
        Long fromRuleVersionId,
        /** 命中规则的逻辑编码;执行器构造时从 snapshot.code() 填充。 */
        String fromRuleCode,
        /** 命中规则的版本号;执行器从 snapshot.version() 填充。 */
        long fromRuleVersion,
        String category,
        List<RuleVersionSnapshot.DecisionAction> actions
) {
    public Decision {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** 带 category、无 actions 的便捷构造（actions 空）。 */
    public Decision(String code, String name, int priority, Long fromRuleVersionId, String category) {
        this(code, name, priority, fromRuleVersionId, null, 0L, category, List.of());
    }

    /** 无分类、无 actions 的便捷构造（category=null，actions 空）。 */
    public Decision(String code, String name, int priority, Long fromRuleVersionId) {
        this(code, name, priority, fromRuleVersionId, null, 0L, null, List.of());
    }
}
