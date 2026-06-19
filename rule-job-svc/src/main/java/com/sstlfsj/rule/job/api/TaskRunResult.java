package com.sstlfsj.rule.job.api;

/**
 * 一次任务执行的结果(executor 返回,由 dispatcher 写 scheduled_task_execution)。
 *
 * @param status         终态
 * @param processedCount 处理总数(TRIGGER:主体 / INGESTION:标签行)
 * @param successCount   成功数
 * @param errorCount     失败数
 * @param errorSummary   错误摘要(可空)
 */
public record TaskRunResult(TaskExecutionStatus status, int processedCount, int successCount,
                            int errorCount, String errorSummary) {}
