package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TraceScopeTest {
    @Test
    void unbound_defaultsToTrue() {
        assertThat(TraceScope.COLLECT.orElse(true)).isTrue();
    }
    @Test
    void boundFalse_isHonoredWithinScope() {
        boolean inside = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> TraceScope.COLLECT.orElse(true));
        assertThat(inside).isFalse();
        assertThat(TraceScope.COLLECT.orElse(true)).isTrue();   // 出作用域自动解绑
    }
}
