package com.sstlfsj.rule.kernel.internal.evaluator;

/** AST 编译失败处置策略。 */
public enum CompileErrorPolicy {
    /** WARN 日志 + 该规则永久回落解释器(默认，编译版永不劣于解释器)。 */
    FALLBACK,
    /** 抛异常中止(发布期不变量违例，运行期宁可炸不静默)。 */
    FAIL
}
