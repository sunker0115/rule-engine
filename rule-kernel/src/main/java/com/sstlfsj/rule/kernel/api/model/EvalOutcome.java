package com.sstlfsj.rule.kernel.api.model;

/**
 * 引擎一次评估的聚合输出：结果 + 组装好的上下文。
 * 调用方复用 context 做快照持久化，避免二次取数；候选为空或 Pre-Gate 全拦截时 context 为 null。
 */
public record EvalOutcome(EvalResult result, EvalContext context) {
}