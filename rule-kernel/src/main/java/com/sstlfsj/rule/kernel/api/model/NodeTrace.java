package com.sstlfsj.rule.kernel.api.model;

import java.util.List;

/**
 * AST 单节点的求值 trace 记录，用于 dry-run 结果展示和审计。
 * expectedValue 为叶子条件的期望/阈值快照（取自 ConditionNode.params），随 trace 行落库到 params 列；
 * displayLabel 为叶子条件的可读标签快照（取自 ConditionNode.displayLabel），随 trace 行落库到 display_label 列。
 * 容器节点（And/Or/Not/Xor/IfNode/DecisionTableRow/ScorecardRoot）二者为 null。
 */
public record NodeTrace(
        String nodeType,
        String conditionType,
        String metricCode,
        Boolean result,
        Object actualValue,
        String valueSource,
        String errorCode,
        List<NodeTrace> children,
        /** 所属规则版本 ID，由执行器在顶层 trace 上填充后向下透传写库；响应体可忽略。 */
        Long ruleVersionId,
        Object expectedValue,
        String displayLabel
) {
    public NodeTrace {
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** 兼容构造：不带 expectedValue/displayLabel 的旧 9 参形态（容器节点用）。 */
    public NodeTrace(String nodeType, String conditionType, String metricCode,
                     Boolean result, Object actualValue, String valueSource, String errorCode,
                     List<NodeTrace> children, Long ruleVersionId) {
        this(nodeType, conditionType, metricCode, result, actualValue, valueSource, errorCode,
                children, ruleVersionId, null, null);
    }
}
