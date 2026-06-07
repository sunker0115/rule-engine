package com.sstlfsj.rule.job.api.dto;

import java.time.LocalDateTime;

/**
 * Job 执行记录响应 VO。
 *
 * @param id                执行记录主键（即 jobRunId）
 * @param jobDefinitionId   归属 Job
 * @param tenantId          租户 ID
 * @param triggerAt         触发时间
 * @param status            RUNNING / SUCCESS / PARTIAL_FAIL / FAILED
 * @param subjectCount      主体总数
 * @param successCount      成功注入数
 * @param errorCount        失败数
 * @param errorSummary      错误摘要
 * @param finishedAt        完成时间（未完成为 null）
 */
public record JobExecutionVO(
        Long id,
        Long jobDefinitionId,
        String tenantId,
        LocalDateTime triggerAt,
        String status,
        int subjectCount,
        int successCount,
        int errorCount,
        String errorSummary,
        LocalDateTime finishedAt
) {}
