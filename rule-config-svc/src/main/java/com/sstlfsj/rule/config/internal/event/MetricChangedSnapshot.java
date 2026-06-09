package com.sstlfsj.rule.config.internal.event;

/**
 * 指标变更快照：CREATE / UPDATE metric_definition 时记录指标编码、版本与是否破坏性变更。
 *
 * @param metricCode 指标编码
 * @param version    变更后版本号
 * @param breaking   是否破坏性变更（CREATE 时为 null；UPDATE 时 true=升版、false=原地更新）
 */
public record MetricChangedSnapshot(String metricCode, int version, Boolean breaking) implements AuditSnapshot {
}
