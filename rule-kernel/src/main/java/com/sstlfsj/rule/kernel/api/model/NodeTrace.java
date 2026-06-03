package com.sstlfsj.rule.kernel.api.model;

import java.util.List;

/** AST 单节点的求值 trace 记录，用于 dry-run 结果展示和审计。 */
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
        Long ruleVersionId
) {
    public NodeTrace {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
