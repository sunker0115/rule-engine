package com.sstlfsj.rule.job.api;

/**
 * 一次任务运行的上下文。
 *
 * @param taskRunId scheduled_task_execution.id（每次运行唯一，用作 eventId 幂等键）
 * @param taskId    scheduled_task.id（任务定义主键，供有状态 executor 写回 config）
 * @param tenantId  租户 id
 */
public record TaskRunContext(long taskRunId, long taskId, long tenantId) {}
