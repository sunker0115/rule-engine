package com.sstlfsj.rule.kernel.api.model;

import java.util.List;

/** 一次规则版本评估的最终结果。 */
public record EvalResult(
        boolean ruleHit,
        Decision finalDecision,
        List<Decision> hitDecisions,
        List<NodeTrace> nodeTrace,
        String errorCode,
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
    }

    /** 规则命中时的标准结果（AST_BOOLEAN kind）。 */
    public static EvalResult hit() {
        return new EvalResult(true, null, List.of(), List.of(), null, null, null, null);
    }

    /** 规则未命中时的标准结果（AST_BOOLEAN kind）。 */
    public static EvalResult miss() {
        return new EvalResult(false, null, List.of(), List.of(), null, null, null, null);
    }

    /**
     * 未命中但携带已收集 trace（评估完整树后无命中，仍需回 trace）。
     *
     * @param nodeTrace 已收集的 NodeTrace 列表
     * @return 不命中结果
     */
    public static EvalResult miss(List<NodeTrace> nodeTrace) {
        return new EvalResult(false, null, List.of(), nodeTrace, null, null, null, null);
    }

    /**
     * 不命中 + 错误码，无 trace（AST 类型不符等早退场景）。
     *
     * @param errorCode 错误码
     * @return 错误结果
     */
    public static EvalResult error(String errorCode) {
        return new EvalResult(false, null, List.of(), List.of(), errorCode, null, null, null);
    }

    /**
     * 不命中 + 错误码，携带已收集 trace（条件求值出错中止）。
     *
     * @param errorCode 错误码
     * @param nodeTrace 已收集的 NodeTrace 列表
     * @return 错误结果
     */
    public static EvalResult error(String errorCode, List<NodeTrace> nodeTrace) {
        return new EvalResult(false, null, List.of(), nodeTrace, errorCode, null, null, null);
    }
}
