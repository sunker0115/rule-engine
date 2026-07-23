package com.sstlfsj.rule.kernel.internal.codec;

/**
 * 数据库 JOIN 查询结果行，供 SnapshotAssembler 装配 RuleVersionSnapshot 使用。
 * 纯数据载体，无 Spring 依赖。
 * {@code decisionStrategy} 来自 scene 表，场景级字段，供 SceneSnapshotLoader 写入 SceneRuleIndex。
 */
public record RuleVersionRow(
        Long ruleVersionId,
        String sceneCode,
        Long tenantId,
        /** rule_version.body JSON（多态 RuleBody：AstBody/ScriptBody/FlowBody，含 type 判别）。 */
        String bodyJson,
        String preGatesJson,
        String decisionBindingsJson,
        String triggerEventTypesJson,
        String kind,
        String decisionStrategy,
        /** rule_version.metric_dependencies JSON 数组字符串；可能为 null（旧行容错）。 */
        String metricDependenciesJson,
        /** rule_version.payload_dependencies JSON 数组字符串；可能为 null（旧行容错）。 */
        String payloadDependenciesJson,
        /** rule_definition.code 逻辑编码。 */
        String code,
        /** rule_version.version 版本号。 */
        long version,
        /** scene.default_params JSON 字符串(scene 级,供 SceneSnapshotLoader 写 SceneRuleIndex);可能为 null。 */
        String defaultParamsJson
) {
}
