package com.sstlfsj.rule.eval.api.service;

/**
 * OUTCOME_INGESTION 任务配置(静态定义):从 source 增量拉真实结果标签 upsert decision_outcome。
 *
 * <p>独立 typed record,不实现共享基类(去中心化);运行态游标(watermark)不在此,经 TaskRunContext.cursor 入、
 * TaskRunResult.newCursor 出,由调度框架写 scheduled_task.run_cursor 列。
 *
 * @param source 标签来源(SQL 等)
 */
public record OutcomeIngestionConfig(OutcomeSourceConfig source) {}
