package com.sstlfsj.rule.kernel.api.spi.expression;

/** 表达式运行期求值失败(类型不匹配、缺失绑定、内建函数执行错等);上游执行器捕获并兜成 SCRIPT_EVAL_ERROR。 */
public class ExpressionEvaluateException extends RuntimeException {
    public ExpressionEvaluateException(String message) {
        super(message);
    }

    public ExpressionEvaluateException(String message, Throwable cause) {
        super(message, cause);
    }
}
