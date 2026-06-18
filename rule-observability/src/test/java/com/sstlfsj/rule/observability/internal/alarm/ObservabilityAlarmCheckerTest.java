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
    void errorRate_incrementalWindow_secondHealthyWindow_doesNotAlarm() {
        // 滑动窗口核心：第二个健康窗口不应被第一窗口的历史错误拉高而误告警（累计模式会，增量模式不会）
        Counter total = Counter.builder(RuleMetrics.EVAL_TOTAL).register(registry);
        Counter error = Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL).register(registry);
        // 第一窗口：100 评估 10 错（10% > 5%）→ 告警
        total.increment(100);
        error.increment(10);
        checker.check();
        verify(publisher, times(1)).publishEvent(any(EvalAlarmEvent.class));

        // 第二窗口：再 100 评估、0 错 → 本周期 0%（累计仍 200 评估/10 错=5% 卡阈值）→ 增量窗口不告警
        total.increment(100);
        checker.check();
        verify(publisher, times(1)).publishEvent(any(EvalAlarmEvent.class));   // 总次数仍 1，第二窗口未误报
    }

    @Test
    void errorRate_incrementalWindow_secondBadWindow_alarmsAgain() {
        // 第二窗口持续高错误 → 应再次告警（增量窗口对当前健康度敏感）
        Counter total = Counter.builder(RuleMetrics.EVAL_TOTAL).register(registry);
        Counter error = Counter.builder(RuleMetrics.EVAL_ERROR_TOTAL).register(registry);
        total.increment(100);
        error.increment(10);
        checker.check();   // 告警 1

        total.increment(100);
        error.increment(20);   // 本窗口 20/100=20% → 再告警
        checker.check();

        verify(publisher, times(2)).publishEvent(any(EvalAlarmEvent.class));
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
