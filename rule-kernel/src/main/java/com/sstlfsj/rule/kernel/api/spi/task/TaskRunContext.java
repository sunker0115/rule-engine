package com.sstlfsj.rule.kernel.api.spi.task;

/**
 * 一次任务运行的上下文。
 *
 * @param taskRunId scheduled_task_execution.id（每次运行唯一，用作 eventId 幂等键）
 * @param taskId    scheduled_task.id（任务定义主键）
 * @param tenantId  租户 id
 * @param cursor    当前 run_cursor（增量任务的运行游标，可空；executor 经 ctx 读、经 result 写回，不碰 scheduled_task 表）
 */
public record TaskRunContext(long taskRunId, long taskId, long tenantId, String cursor) {}
