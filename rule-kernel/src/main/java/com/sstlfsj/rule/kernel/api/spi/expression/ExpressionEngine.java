package com.sstlfsj.rule.kernel.api.spi.expression;

import java.util.Map;

/**
 * 受限表达式引擎 SPI:编译为可缓存的 CompiledExpression,按只读变量绑定求值。
 * 实现须线程安全(单例共享);实现内部应按源码内容哈希缓存编译产物。
 */
public interface ExpressionEngine {
    /**
     * 引擎标识,与 {@link com.sstlfsj.rule.kernel.api.model.ScriptSource#lang()} 路由匹配(如 "CEL")。
     * @return 引擎语言标签
     */
    String lang();

    /**
     * 编译源码(含语法/类型检查);失败抛 {@link ExpressionCompileException}。
     * @param source 表达式源码
     * @return 编译产物(实现可缓存)
     * @throws ExpressionCompileException 语法错、未知变量、类型不符等编译/类型检查失败
     */
    CompiledExpression compile(String source);

    /**
     * 对编译产物按只读变量绑定求值。
     * @param compiled 编译产物
     * @param bindings 顶层变量绑定(键如 "metrics"/"payload"/"subject"/"now")
     * @return Boolean / String / Number 之一,或 null(不命中)
     */
    Object evaluate(CompiledExpression compiled, Map<String, Object> bindings);

    /**
     * 发布期类型检查:按被引用变量的声明类型环境编译校验源码(只 check 不 eval)。
     *
     * <p>强类型引擎(如 CEL)据此捕获类型不符(如 string 字段参与数值比较);无类型系统的弱引擎
     * 默认 no-op,仅靠 {@link #compile} 的语法检查兜底。
     *
     * @param source  表达式源码
     * @param typeEnv 被引用变量的声明类型环境
     * @throws ExpressionCompileException 类型检查失败(类型不符等)
     */
    default void typeCheck(String source, ScriptTypeEnv typeEnv) {
        // 默认 no-op:无类型系统的弱引擎降级,仅靠 compile 的语法检查
    }
}
