package com.sstlfsj.rule.eval.api;

/**
 * 取数分阶段 trace（自助测试用）。HTTP：renderedRequest/rawResponse/successMatched/mappedValue/errorCode；
 * SQL：boundSql/rawResponse/mappedValue/errorCode。按源填相应字段，未用字段为 null。
 *
 * @param sourceType      源类型
 * @param renderedRequest HTTP 渲染后请求（method url headers body 文本）
 * @param boundSql        SQL 绑定后语句
 * @param rawResponse     HTTP 原始响应体 / SQL 原始首行文本
 * @param successMatched  HTTP successWhen 判定结果
 * @param mappedValue     映射/强转后的值
 * @param errorCode       命中的 MetricFetchError 名，成功为 null
 */
public record FetchTrace(
        String sourceType,
        String renderedRequest,
        String boundSql,
        String rawResponse,
        Boolean successMatched,
        Object mappedValue,
        String errorCode) {}
