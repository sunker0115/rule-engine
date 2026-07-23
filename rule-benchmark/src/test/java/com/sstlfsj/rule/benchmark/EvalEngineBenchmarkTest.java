package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.kernel.api.model.EvalOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 校准测试：确保 EvalEngineBenchmark 的装配真正走通命中路径（基准测的是有效评估）。 */
class EvalEngineBenchmarkTest {

    private static void assertHit(EvalEngineBenchmark b) {
        EvalOutcome outcome = b.evaluate();
        assertThat(outcome.result().ruleHit()).isTrue();
        assertThat(outcome.context()).isNotNull();
        assertThat(outcome.context().metrics()).containsKeys("m0", "m1", "m2");
    }

    @Test
    void sequential_highestPriority_hits() {
        EvalEngineBenchmark b = new EvalEngineBenchmark();
        b.n = 5;
        b.mode = "SEQUENTIAL";
        b.strategy = "HIGHEST_PRIORITY";
        b.setup();
        assertHit(b);
    }

    @Test
    void parallel_highestPriority_hits() {
        EvalEngineBenchmark b = new EvalEngineBenchmark();
        b.n = 5;
        b.mode = "PARALLEL";
        b.strategy = "HIGHEST_PRIORITY";
        b.setup();
        assertHit(b);
    }

    @Test
    void parallel_firstHit_hits() {
        EvalEngineBenchmark b = new EvalEngineBenchmark();
        b.n = 10;
        b.mode = "PARALLEL";
        b.strategy = "FIRST_HIT";
        b.setup();
        assertHit(b);
    }
}
