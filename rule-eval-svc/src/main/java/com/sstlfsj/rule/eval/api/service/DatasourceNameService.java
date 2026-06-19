package com.sstlfsj.rule.eval.api.service;

import java.util.List;
import java.util.Set;

/** 已注册数据源名列表（供 OUTCOME_INGESTION 创建表单选择）。 */
public interface DatasourceNameService {
    /** @return MetricDataSourceRegistry 中已注册的全部逻辑数据源名。 */
    Set<String> registeredNames();

    /**
     * 查指定数据源下的所有用户表名（INFORMATION_SCHEMA.TABLES, BASE TABLE）。
     *
     * @param datasourceName 数据源逻辑名
     * @return 表名列表（按名字母序）；数据源不存在返回空列表
     */
    List<String> tables(String datasourceName);

    /**
     * 查指定数据源+表的列名列表（INFORMATION_SCHEMA.COLUMNS）。
     *
     * @param datasourceName 数据源逻辑名
     * @param tableName      表名
     * @return 列名列表（按 ORDINAL_POSITION）；数据源或表不存在返回空列表
     */
    List<String> columns(String datasourceName, String tableName);
}
