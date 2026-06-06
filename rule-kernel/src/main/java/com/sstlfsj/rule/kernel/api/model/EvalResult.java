package com.sstlfsj.rule.kernel.api.model;

import java.util.List;

/** 一次规则版本评估的最终结果。 */
public record EvalResult(
        boolean ruleHit,
        Decision finalDecision,
        List<Decision> hitDecisions,
        List<NodeTrace> nodeTrace,
        String errorCode,
        List<ActionResult> actionResults,
        /** D12 SCORECARD kind 的累计分；AST_BOOLEAN kind 时为 null。 */
        Double score,
        /** D42 DECISION_TREE 命中叶子节点时填充的分类标签；其他 kind 为 null。 */
        String category,
        /** D42 DECISION_TABLE 命中行时填充的决策码；其他 kind 为 null（与 finalDecision.code() 相同）。 */
        String decision
) {
    public EvalResult {
        hitDecisions = hitDecisions == null ? List.of() : List.copyOf(hitDecisions);
        nodeTrace = nodeTrace == null ? List.of() : List.copyOf(nodeTrace);
        actionResults = actionResults == null ? List.of() : List.copyOf(actionResults);
    }

    /** 规则命中时的标准结果（AST_BOOLEAN kind）。 */
    public static EvalResult hit() {
        return new EvalResult(true, null, List.of(), List.of(), null, List.of(), null, null, null);
    }

    /** 规则未命中时的标准结果（AST_BOOLEAN kind）。 */
    public static EvalResult miss() {
        return new EvalResult(false, null, List.of(), List.of(), null, List.of(), null, null, null);
    }
}
