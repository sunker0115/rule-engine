package com.sstlfsj.rule.observability.internal.alarm;

import com.sstlfsj.rule.observability.api.events.EvalAlarmEvent;
import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ObservabilityAlarmCheckerTest {

    private MeterRegistry registry;
    private ApplicationEventPublisher publisher;
    private ObservabilityAlarmProperties props;
    private ObservabilityAlarmChecker checker;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        publisher = mock(ApplicationEventPublisher.class);
        props = new ObservabilityAlarmProperties();  // 默认值 0.05 / 0.8
        checker = new ObservabilityAlarmChecker(registry, props, publisher);
    }

    @Test
    void errorRate_above_threshold_publishes_alarm() {
        Counter total = Counter.builder(RuleMetrics.EVAL_TOTAL).register(registry);
        Counter error = Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL).register(registry);
        total.increment(100);
        error.increment(10);  // rate=0.10 > 0.05

        checker.check();

        ArgumentCaptor<EvalAlarmEvent> cap = ArgumentCaptor.forClass(EvalAlarmEvent.class);
        verify(publisher).publishEvent(cap.capture());
        assertThat(cap.getValue().metric()).isEqualTo(RuleMetrics.EVAL_ERROR_TOTAL);
        assertThat(cap.getValue().actual()).isEqualTo(0.1);
    }

    @Test
    void errorRate_below_threshold_no_alarm() {
        Counter total = Counter.builder(RuleMetrics.EVAL_TOTAL).register(registry);
        Counter error = Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL).register(registry);
        total.increment(100);
        error.increment(4);  // 4% < 5%

        checker.check();

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void queue_above_threshold_publishes_alarm() {
        // double[] 持有 gauge 状态，避免引入 Guava AtomicDouble 依赖
        double[] util = {0.9};
        Gauge.builder(RuleMetrics.TRACE_QUEUE_SIZE, util, u -> u[0]).register(registry);

        checker.check();

        ArgumentCaptor<EvalAlarmEvent> cap = ArgumentCaptor.forClass(EvalAlarmEvent.class);
        verify(publisher).publishEvent(cap.capture());
        assertThat(cap.getValue().metric()).isEqualTo(RuleMetrics.TRACE_QUEUE_SIZE);
    }

    @Test
    void total_zero_no_alarm() {
        checker.check();
        verify(publisher, never()).publishEvent(any());
    }
}
