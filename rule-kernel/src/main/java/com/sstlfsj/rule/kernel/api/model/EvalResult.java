package com.sstlfsj.rule.kernel.api.model;

import java.util.List;

/** 一次规则版本评估的最终结果。 */
public record EvalResult(
        boolean ruleHit,
        Decision finalDecision,
        List<Decision> hitDecisions,
        List<NodeTrace> nodeTrace,
        String errorCode,
        List<ActionResult> actionResults
) {
    public EvalResult {
        hitDecisions = hitDecisions == null ? List.of() : List.copyOf(hitDecisions);
        nodeTrace = nodeTrace == null ? List.of() : List.copyOf(nodeTrace);
        actionResults = actionResults == null ? List.of() : List.copyOf(actionResults);
    }

    /** 规则未命中时的标准结果。 */
    public static EvalResult miss() {
        return new EvalResult(false, null, List.of(), List.of(), null, List.of());
    }
}
