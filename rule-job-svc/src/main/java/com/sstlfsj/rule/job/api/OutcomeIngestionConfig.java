package com.sstlfsj.rule.job.api;

import com.sstlfsj.rule.eval.api.service.OutcomeSourceConfig;

import java.time.Instant;

/**
 * OUTCOME_INGESTION 任务配置:从 source 增量拉真实结果标签 upsert decision_outcome。
 *
 * @param source    标签来源(SQL 等),eval-svc 定义
 * @param watermark 运行态游标(上次拉到的 max labeledAt;null=首次全量),executor 跑完写回
 */
public record OutcomeIngestionConfig(OutcomeSourceConfig source, Instant watermark) implements TaskConfig {
    @Override public TaskType type() { return TaskType.OUTCOME_INGESTION; }
}
