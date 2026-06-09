package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;
import java.util.Map;

/**
 * 规则导出 / 导入自包含 Bundle（B7 / 08-evolution §2.9）。
 * <p>多规则结构：{@code rules} 为本次导出的规则版本集合，{@code scenes} / {@code metricDefinitions} /
 * {@code decisionDefinitions} 为跨规则去重的依赖定义，{@code actionTypeManifest} 为去重 actionType 清单。
 * 所有结构化字段（conditionAst / decisionBindings / preGates / triggerEventTypes /
 * payloadSchema / eventTypes / defaultParams / actions）以 typed 对象无损搬运，
 * 持久层 TypeHandler 负责 JSON 列序列化，导入端不做重解析。</p>
 *
 * @param bundleVersion       Bundle schema 版本，当前固定 1
 * @param exportedAt          导出时间 ISO-8601
 * @param sourceTenantId      源租户 id（诊断用，导入不照搬，目标租户由调用参数决定）
 * @param rules               规则集合（每条含标识 + 当前 ACTIVE rule_version 内容）
 * @param scenes              规则引用的 Scene 快照（去重）
 * @param metricDefinitions   规则 metricDependencies 引用的 metric 定义（去重，按精确版本）
 * @param decisionDefinitions decisionBindings 引用的 tenant 级 decision 定义（去重）
 * @param actionTypeManifest  decisions 内出现的 actionType 去重清单（目标环境 SPI 兼容性核对）
 */
public record RuleBundle(
        int bundleVersion,
        String exportedAt,
        String sourceTenantId,
        List<RuleEntry> rules,
        List<SceneSnapshot> scenes,
        List<MetricEntry> metricDefinitions,
        List<DecisionEntry> decisionDefinitions,
        List<String> actionTypeManifest
) {
    /** 规则主体：标识来自 rule_definition，版本内容来自当前 ACTIVE rule_version；sceneCode 关联 scenes 元素。 */
    public record RuleEntry(
            String code,
            String name,
            String kind,
            String sceneCode,
            AstNode conditionAst,
            List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates,
            List<String> triggerEventTypes,
            List<MetricDependency> metricDependencies
    ) {}

    /** Scene 快照，对应 scene 表可重建字段。 */
    public record SceneSnapshot(
            String code,
            String name,
            String description,
            String subjectType,
            String dominantMode,
            String decisionStrategy,
            List<String> eventTypes,
            List<PayloadFieldSpec> payloadSchema,
            Map<String, Object> defaultParams,
            Integer payloadSchemaVersion
    ) {}

    /** metric 定义快照，对应 metric_definition 表的精确版本行。 */
    public record MetricEntry(
            String metricCode,
            Integer version,
            String name,
            String sourceType,
            String dataType,
            String params,
            Integer cacheTtlSeconds,
            Boolean allowProvided
    ) {}

    /** decision 定义快照，对应 decision_definition 表（actions 为原始 JSON）。 */
    public record DecisionEntry(
            String code,
            String name,
            Integer priority,
            String description,
            String actions
    ) {}
}
