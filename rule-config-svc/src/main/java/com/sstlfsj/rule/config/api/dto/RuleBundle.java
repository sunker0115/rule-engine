package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;
import java.util.Map;

/**
 * 规则导出/导入自包含 Bundle v2（{@code formatVersion=2}，与 v1 不兼容）。
 *
 * <p>v2 改进：
 * <ul>
 *   <li>{@code revision}：整 Bundle 内容 SHA-256，可快速判断两份 Bundle 是否等价。</li>
 *   <li>{@link RuleEntry#script}：EXPRESSION_SCRIPT 规则的脚本源码随 Bundle 携带，不再丢失。</li>
 *   <li>{@link RuleEntry#contentHash}：规则内容 SHA-256，import 时用于幂等判断（相同 hash 跳过）。</li>
 * </ul>
 *
 * @param formatVersion       Bundle schema 版本，当前固定 {@code 2}
 * @param revision            整 Bundle 内容 SHA-256（export 时生成）
 * @param exportedAt          导出时间 ISO-8601
 * @param sourceTenant        源租户编码（诊断用，import 时忽略，目标租户由调用参数决定）
 * @param rules               规则集合（每条含当前 ACTIVE rule_version 完整内容）
 * @param scenes              规则引用的 Scene 快照（去重）
 * @param metricDefinitions   规则 metricDependencies 引用的 metric 定义（去重，按精确版本）
 * @param decisionDefinitions decisionBindings 引用的 tenant 级 decision 定义（去重）
 */
public record RuleBundle(
        int formatVersion,
        String revision,
        String exportedAt,
        String sourceTenant,
        List<RuleEntry> rules,
        List<SceneSnapshot> scenes,
        List<MetricEntry> metricDefinitions,
        List<DecisionEntry> decisionDefinitions
) {
    /**
     * 规则主体：标识来自 rule_definition，版本内容来自当前 ACTIVE rule_version。
     * sceneCode 关联 scenes 元素。
     */
    public record RuleEntry(
            String code,
            String name,
            String kind,
            String sceneCode,
            AstNode conditionAst,
            List<DecisionBinding> decisionBindings,
            List<PreGateConfig> preGates,
            List<String> triggerEventTypes,
            List<MetricDependency> metricDependencies,
            List<PayloadDependency> payloadDependencies,
            /** EXPRESSION_SCRIPT 规则的脚本载体；其他 kind 为 null。 */
            ScriptSource script,
            /** 规则内容 SHA-256（conditionAst/bindings/preGates/kind/triggers/script），import 幂等判断用。 */
            String contentHash
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
            Map<String, Object> defaultParams
    ) {}

    /** metric 定义快照，对应 metric_definition 表的精确版本行。 */
    public record MetricEntry(
            String metricCode,
            Integer version,
            String name,
            String sourceType,
            String dataType,
            Map<String, Object> params,
            Integer cacheTtlSeconds,
            Boolean allowProvided
    ) {}

    /** decision 定义快照，对应 decision_definition 表。 */
    public record DecisionEntry(
            String code,
            String name,
            Integer priority,
            String description
    ) {}
}
