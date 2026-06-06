package com.sstlfsj.rule.config.api.dto;

import java.util.List;

/**
 * 规则导入结果（B7，批量）。{@code rules} 逐条记录每个规则的落库信息，其余字段为 Bundle 级依赖 upsert 的处置清单。
 *
 * @param rules                   逐条规则导入结果
 * @param scenesCreated           缺失而自动创建的 sceneCode
 * @param scenesSkippedExisting   已存在跳过的 sceneCode
 * @param metricsCreated          缺失而自动创建的 metricCode（非 SQL 类）
 * @param metricsSkippedExisting  已存在跳过的 metricCode
 * @param metricsRequiringReview  SQL_AGGREGATE 类缺失、未自动创建、需人工审核的 metricCode
 * @param decisionsCreated        缺失而自动创建的 decision code
 * @param decisionsSkippedExisting 已存在跳过的 decision code
 * @param actionTypesReferenced   Bundle 声明引用的 actionType（提醒目标环境核对 SPI handler 注册）
 */
public record RuleImportResult(
        List<ImportedRule> rules,
        List<String> scenesCreated,
        List<String> scenesSkippedExisting,
        List<String> metricsCreated,
        List<String> metricsSkippedExisting,
        List<String> metricsRequiringReview,
        List<String> decisionsCreated,
        List<String> decisionsSkippedExisting,
        List<String> actionTypesReferenced
) {
    /**
     * 单条规则导入落库结果。
     *
     * @param ruleDefinitionId   规则定义 id（新建或既有）
     * @param ruleVersionId      本次写入的 DRAFT rule_version id
     * @param version            本次草稿版本号
     * @param code               规则编码
     * @param sceneCode          所属 Scene 编码
     * @param ruleAlreadyExisted true=同 code 规则已存在，本次为追加草稿版本；false=新建
     */
    public record ImportedRule(
            Long ruleDefinitionId,
            Long ruleVersionId,
            Long version,
            String code,
            String sceneCode,
            boolean ruleAlreadyExisted
    ) {}
}
