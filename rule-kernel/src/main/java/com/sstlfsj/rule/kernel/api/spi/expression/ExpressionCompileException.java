package com.sstlfsj.rule.kernel.api.spi.expression;

/** 表达式编译/类型检查失败(语法错、未知变量、类型不符等);发布期校验捕获并拒绝。 */
public class ExpressionCompileException extends RuntimeException {
    public ExpressionCompileException(String message) {
        super(message);
    }

    public ExpressionCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
