package com.sstlfsj.rule.kernel.api.model;

/** Pre-Gate 评估结果，blockedBy 记录拦截的 Gate 类型。 */
public record PreGateResult(
        boolean passed,
        String blockedBy
) {
    public static PreGateResult pass() {
        return new PreGateResult(true, null);
    }

    public static PreGateResult blocked(String gateType) {
        return new PreGateResult(false, gateType);
    }
}
