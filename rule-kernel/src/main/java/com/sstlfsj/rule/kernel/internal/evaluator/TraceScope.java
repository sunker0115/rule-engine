package com.sstlfsj.rule.kernel.internal.evaluator;

/** 逐次评估的 ambient 执行模式:是否收集 NodeTrace。EvalEngine 入口绑定,执行器读取。 */
public final class TraceScope {
    /** 未绑定时默认 true(= 现状"始终收集";直调执行器的测试无需感知)。 */
    public static final ScopedValue<Boolean> COLLECT = ScopedValue.newInstance();
    private TraceScope() {}
}
