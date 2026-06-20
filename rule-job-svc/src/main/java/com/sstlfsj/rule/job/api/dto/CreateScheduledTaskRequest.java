package com.sstlfsj.rule.job.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建 OUTCOME_INGESTION 调度任务请求（SQL-direct 源）。
 * TRIGGER 任务由 {@code @TriggerTask} 注解 seed，不走此接口。
 *
 * @param tenantId   租户 id
 * @param code       任务编码（租户内唯一）
 * @param name       展示名称
 * @param cron       Spring 6 段 cron（秒 分 时 日 月 周），如 {@code 0 0 2 * * *}
 * @param datasource MetricDataSourceRegistry 已注册的数据源名
 * @param sql        标签拉取 SQL（须含固定列别名 event_id/outcome_label/outcome_value/labeled_at；
 *                   可绑定 :tenantId / :watermark）
 */
public record CreateScheduledTaskRequest(
        @NotNull Long tenantId,
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String cron,
        @NotBlank String datasource,
        @NotBlank String sql) {}
