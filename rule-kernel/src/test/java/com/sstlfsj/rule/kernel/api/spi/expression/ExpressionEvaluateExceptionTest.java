package com.sstlfsj.rule.kernel.api.spi.expression;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionEvaluateExceptionTest {

    @Test
    void carriesMessage() {
        ExpressionEvaluateException ex = new ExpressionEvaluateException("求值失败");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("求值失败");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void carriesMessageAndCause() {
        Throwable cause = new IllegalStateException("根因");
        ExpressionEvaluateException ex = new ExpressionEvaluateException("求值失败", cause);
        assertThat(ex.getMessage()).isEqualTo("求值失败");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
