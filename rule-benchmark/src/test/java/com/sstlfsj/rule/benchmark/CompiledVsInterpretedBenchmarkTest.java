package com.sstlfsj.rule.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 校准测试：确保 A/B 基准两条路径都真命中，且编译版与解释器结果一致(平价冒烟)。 */
class CompiledVsInterpretedBenchmarkTest {

    @Test
    void setupProducesHit_bothPathsAgree() throws Exception {
        CompiledVsInterpretedBenchmark b = new CompiledVsInterpretedBenchmark();
        b.n = 5;
        b.setup();

        assertThat(b.interpreted()).isTrue();
        assertThat(b.compiled()).isTrue();
        assertThat(b.compiled()).isEqualTo(b.interpreted());
    }
}
