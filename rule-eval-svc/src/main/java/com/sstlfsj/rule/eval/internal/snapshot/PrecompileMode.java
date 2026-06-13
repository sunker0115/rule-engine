package com.sstlfsj.rule.eval.internal.snapshot;

/** 脚本规则预编译加热模式。 */
public enum PrecompileMode {
    /** 延迟加热:首次评估时编译入引擎缓存(默认,零启动成本)。 */
    LAZY,
    /** 预加热:快照加载期(启动 / 发布热更)预编译入引擎缓存,首次评估即命中。 */
    EAGER
}
