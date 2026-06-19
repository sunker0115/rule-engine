package com.sstlfsj.rule.job.api;

import com.sstlfsj.rule.eval.api.service.OutcomeSourceConfig;

/**
 * OUTCOME_INGESTION 任务配置(静态定义):从 source 增量拉真实结果标签 upsert decision_outcome。
 * 运行态游标(watermark)不在此,存于 scheduled_task.run_cursor 列。
 *
 * @param source 标签来源(SQL 等)
 */
public record OutcomeIngestionConfig(OutcomeSourceConfig source) implements TaskConfig {
    @Override public TaskType type() { return TaskType.OUTCOME_INGESTION; }
}
