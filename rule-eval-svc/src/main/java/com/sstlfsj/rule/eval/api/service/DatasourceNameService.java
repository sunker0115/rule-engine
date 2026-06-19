package com.sstlfsj.rule.eval.api.service;

import java.util.Set;

/** 已注册数据源名列表（供 OUTCOME_INGESTION 创建表单选择）。 */
public interface DatasourceNameService {
    /** @return MetricDataSourceRegistry 中已注册的全部逻辑数据源名。 */
    Set<String> registeredNames();
}
