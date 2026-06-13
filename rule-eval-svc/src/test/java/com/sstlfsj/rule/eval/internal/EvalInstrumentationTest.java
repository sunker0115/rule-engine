package com.sstlfsj.rule.eval.internal;

import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvalInstrumentationTest {

    private SimpleMeterRegistry registry;
    private Counter evalTotal;
    private Counter evalError;
    private EvalInstrumentation instrumentation;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        evalTotal = Counter.builder(RuleMetrics.EVAL_TOTAL).register(registry);
        evalError = Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL).register(registry);
        instrumentation = new EvalInstrumentation(evalTotal, evalError, registry);
    }

    @Test
    void record_alwaysIncrementsTotal() {
        instrumentation.record(false);
        assertThat(evalTotal.count()).isEqualTo(1.0);
        assertThat(evalError.count()).isZero();
    }

    @Test
    void record_withError_incrementsBoth() {
        instrumentation.record(true);
        assertThat(evalTotal.count()).isEqualTo(1.0);
        assertThat(evalError.count()).isEqualTo(1.0);
    }

    @Test
    void record_withoutError_doesNotIncrementError() {
        instrumentation.record(false);
        instrumentation.record(false);
        assertThat(evalError.count()).isZero();
    }
}
