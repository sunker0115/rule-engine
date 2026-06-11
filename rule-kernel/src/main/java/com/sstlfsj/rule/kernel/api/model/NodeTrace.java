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
        /** 所属规则逻辑编码;顶层 trace 由执行器填充后向下透传。 */
        String ruleCode,
        /** 所属规则版本号。 */
        long ruleVersion,
        Object expectedValue,
        String displayLabel
) {
    public NodeTrace {
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** 容器/终点节点：无 conditionType/metric/actual/expected/label，只有类型+结果+子节点。 */
    public static NodeTrace container(NodeType type, Boolean result, List<NodeTrace> children, Long ruleVersionId) {
        return new NodeTrace(type.tag(), null, null, result, null, null, null, children, ruleVersionId, null, 0L, null, null);
    }

    /** 容器/终点节点 + 规则编码/版本号（顶层 trace 由执行器填充，向下透传）。 */
    public static NodeTrace container(NodeType type, Boolean result, List<NodeTrace> children,
                                      Long ruleVersionId, String ruleCode, long ruleVersion) {
        return new NodeTrace(type.tag(), null, null, result, null, null, null, children, ruleVersionId, ruleCode, ruleVersion, null, null);
    }

    /** 容器节点 + 错误码（取数失败中止时，容器仍记录 errorCode 与已收集子节点）。 */
    public static NodeTrace container(NodeType type, Boolean result, String errorCode,
                                      List<NodeTrace> children, Long ruleVersionId) {
        return new NodeTrace(type.tag(), null, null, result, null, null, errorCode, children, ruleVersionId, null, 0L, null, null);
    }
}
