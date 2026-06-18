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
    // 滑动窗口基线：上次 check 时的累计计数快照，用于算"本周期（自上次 check）增量错误率"。
    // @Scheduled fixedDelay 串行触发，无重入，普通字段即可（无需同步）。
    private double lastTotal = 0;
    private double lastErrors = 0;

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
        // 用增量滑动窗口而非进程累计：累计 rate 在 warm-up 后会被巨大分母稀释（新错误冲不破阈值），
        // 或被早期错误永久拉高（告警latch 不复位）。增量 = 本周期内的真实错误率，对当前健康度敏感。
        double total = count(RuleMetrics.EVAL_TOTAL);
        double errors = count(RuleMetrics.EVAL_ERROR_TOTAL);
        double deltaTotal = total - lastTotal;
        double deltaErrors = errors - lastErrors;
        lastTotal = total;
        lastErrors = errors;
        if (deltaTotal <= 0) return;   // 本周期无新评估（或计数器重置后首拍），跳过
        double rate = deltaErrors / deltaTotal;
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
