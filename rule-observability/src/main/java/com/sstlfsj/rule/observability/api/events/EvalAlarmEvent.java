package com.sstlfsj.rule.observability.api.events;

/**
 * 评估告警事件，由 ObservabilityAlarmChecker 发布。
 * 定义在 observability api 层，任何模块可监听扩展（Webhook / 钉钉等）；
 * v1 由 ObservabilityAlarmListener 打 WARN 日志。
 *
 * @param metric    指标名（RuleMetrics 常量）
 * @param threshold 配置阈值
 * @param actual    当前实测值
 * @param message   可读告警消息
 */
public record EvalAlarmEvent(String metric, double threshold, double actual, String message) {}
