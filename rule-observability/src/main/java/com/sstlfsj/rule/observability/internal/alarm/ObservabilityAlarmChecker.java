package com.sstlfsj.rule.observability.internal.alarm;

import com.sstlfsj.rule.observability.api.events.EvalAlarmEvent;
import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定期检查评估错误率 + trace 队列利用率，超阈值时发布 {@link EvalAlarmEvent}。
 * 从进程内共享 MeterRegistry 读值，零跨模块耦合；告警逻辑全集中在 observability。
 * 由 ObservabilityAutoConfiguration 显式注册（不加 @Component，避免双重注册）。
 */
public class ObservabilityAlarmChecker {

    private final MeterRegistry meterRegistry;
    private final ObservabilityAlarmProperties props;
    private final ApplicationEventPublisher eventPublisher;

    public ObservabilityAlarmChecker(MeterRegistry meterRegistry,
                                     ObservabilityAlarmProperties props,
                                     ApplicationEventPublisher eventPublisher) {
        this.meterRegistry = meterRegistry;
        this.props = props;
        this.eventPublisher = eventPublisher;
    }

    /** 由 @Scheduled 周期触发，依次检查错误率与队列利用率。 */
    @Scheduled(fixedDelayString = "${engine.rule.observability.check-interval-ms:60000}")
    public void check() {
        checkErrorRate();
        checkQueueUtilization();
    }

    void checkErrorRate() {
        double total = count(RuleMetrics.EVAL_TOTAL);
        if (total == 0) return;
        double errors = count(RuleMetrics.EVAL_ERROR_TOTAL);
        double rate = errors / total;
        if (rate > props.getEvalErrorRateThreshold()) {
            eventPublisher.publishEvent(new EvalAlarmEvent(
                    RuleMetrics.EVAL_ERROR_TOTAL,
                    props.getEvalErrorRateThreshold(),
                    rate,
                    String.format("评估错误率 %.1f%% 超过阈值 %.1f%%",
                            rate * 100, props.getEvalErrorRateThreshold() * 100)));
        }
    }

    void checkQueueUtilization() {
        double util = gauge(RuleMetrics.TRACE_QUEUE_SIZE);
        if (util > props.getTraceQueueFullThreshold()) {
            eventPublisher.publishEvent(new EvalAlarmEvent(
                    RuleMetrics.TRACE_QUEUE_SIZE,
                    props.getTraceQueueFullThreshold(),
                    util,
                    String.format("trace 队列利用率 %.0f%% 超过阈值 %.0f%%",
                            util * 100, props.getTraceQueueFullThreshold() * 100)));
        }
    }

    private double count(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c != null ? c.count() : 0.0;
    }

    private double gauge(String name) {
        Gauge g = meterRegistry.find(name).gauge();
        return g != null ? g.value() : 0.0;
    }
}
