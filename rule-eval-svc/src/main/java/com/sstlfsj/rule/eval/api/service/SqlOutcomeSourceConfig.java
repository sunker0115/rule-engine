package com.sstlfsj.rule.eval.api.service;

/**
 * SQL-direct 标签源：在 datasource 上跑 sql 拉标签行。
 * sql 须 SELECT 固定列别名 event_id / outcome_label / outcome_value / labeled_at，
 * 可绑定 :tenantId 与 :watermark（:watermark 为 null 表示首次全量）。
 *
 * @param datasource MetricDataSourceRegistry 已注册的数据源名
 * @param sql        查询语句
 */
public record SqlOutcomeSourceConfig(String datasource, String sql) implements OutcomeSourceConfig {}
