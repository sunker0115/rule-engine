package com.sstlfsj.rule.kernel.api.model;

/**
 * 规则评估执行模式：决定候选规则如何被评估。
 * 与 {@link SceneExecutionStrategy} 正交——策略决定"哪些命中算赢"，模式决定"怎么跑"。
 * 默认 {@link #SEQUENTIAL}。
 */
public enum ExecutionMode {
    /** 逐条串行执行（现状，默认）。 */
    SEQUENTIAL,
    /** StructuredTaskScope + VirtualThread 并发执行所有候选规则。 */
    PARALLEL
}
