package com.sstlfsj.rule.kernel.api.model;

/**
 * 引擎一次评估的聚合输出：结果 + 组装好的上下文 + Pre-Gate 拦截原因。
 * 调用方复用 context 做快照持久化，避免二次取数；候选为空或 Pre-Gate 全拦截时 context 为 null。
 * {@code blockedBy} 非 null 表示候选全部被 Pre-Gate 拦截（D22 BLOCKED 第四态），值为首个阻断的 gateType；
 * 命中 / 评估 MISS / 无候选时为 null。
 */
public record EvalOutcome(EvalResult result, EvalContext context, String blockedBy) {

    /** 未被 Pre-Gate 拦截的常规输出（blockedBy=null）。 */
    public EvalOutcome(EvalResult result, EvalContext context) {
        this(result, context, null);
    }
}
