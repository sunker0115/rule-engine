package com.sstlfsj.rule.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 校准测试：确保 InterpretedExecBenchmark 的 AST(AND N 个 GT 条件)真正命中，基准测的是有效求值。 */
class InterpretedExecBenchmarkTest {

    @Test
    void setupProducesHit() throws Exception {
        InterpretedExecBenchmark b = new InterpretedExecBenchmark();
        b.n = 5;
        b.setup();

        // 5 个 GT(metric=1 > threshold=0) 全真 → AND 命中
        assertThat(b.executeFastPath()).isTrue();
    }
}
