package com.sstlfsj.rule.kernel.api.model;

/**
 * 规则对某 metric 的版本化依赖：发布期冻结的 (metricCode, metricVersion) 对。
 * 评估期据此为每条规则投影"版本特化"的指标视图；JSON 可序列化，作为
 * rule_version.metric_dependencies 数组元素的契约（B7 导出 Bundle 依赖此 schema）。
 */
public record MetricDependency(String metricCode, int metricVersion) {}
