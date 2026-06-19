package com.sstlfsj.rule.job.api.dto;

import java.time.Instant;

/**
 * 调度任务执行记录视图对象。
 *
 * @param id              主键
 * @param scheduledTaskId 所属任务 ID
 * @param status          执行状态
 * @param processedCount  处理主体数
 * @param successCount    成功数
 * @param errorCount      失败数
 * @param errorSummary    错误摘要（可空）
 * @param triggerAt       触发时间
 * @param finishedAt      结束时间（可空）
 */
public record ScheduledTaskExecutionVO(Long id, Long scheduledTaskId, String status, Integer processedCount,
                                       Integer successCount, Integer errorCount, String errorSummary,
                                       Instant triggerAt, Instant finishedAt) {}
