package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.kernel.api.model.EvalOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 校准测试：确保 EvalEngineBenchmark 的装配真正走通命中路径（基准测的是有效评估）。 */
class EvalEngineBenchmarkTest {

    @Test
    void setupProducesHitWithContext() {
        EvalEngineBenchmark b = new EvalEngineBenchmark();
        b.n = 5;
        b.strategy = "HIGHEST_PRIORITY";
        b.setup();

        EvalOutcome outcome = b.evaluate();

        assertThat(outcome.result().ruleHit()).isTrue();
        assertThat(outcome.context()).isNotNull();
        // 3 个 provided metric 全部进入上下文快照
        assertThat(outcome.context().metrics()).containsKeys("m0", "m1", "m2");
    }
}
