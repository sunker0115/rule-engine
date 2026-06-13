package com.sstlfsj.rule.observability.internal.alarm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测告警阈值配置，绑定 engine.rule.observability.* 前缀。
 * 已在 application.yml 占位（eval-error-rate-threshold / trace-queue-full-threshold）。
 */
@Getter
@Setter
@ConfigurationProperties("engine.rule.observability")
public class ObservabilityAlarmProperties {

    /** 评估错误率告警阈值（0~1，默认 5%）。 */
    private double evalErrorRateThreshold = 0.05;
    /** trace 队列利用率告警阈值（0~1，默认 80%）。 */
    private double traceQueueFullThreshold = 0.8;
    /** 检查间隔（毫秒，默认 1 分钟）。 */
    private long checkIntervalMs = 60_000L;
}
