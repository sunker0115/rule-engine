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
        String conditionAstJson,
        String preGatesJson,
        String decisionBindingsJson,
        String triggerEventTypesJson,
        String kind,
        String decisionStrategy,
        /** rule_version.metric_dependencies JSON 数组字符串；可能为 null（旧行容错）。 */
        String metricDependenciesJson
) {
    /**
     * 兼容旧调用点的便利构造（无 metricDependenciesJson，默认 null）。
     *
     * @param ruleVersionId        规则版本 id
     * @param sceneCode            场景编码
     * @param tenantId             租户 id
     * @param conditionAstJson     条件 AST JSON
     * @param preGatesJson         Pre-Gate JSON
     * @param decisionBindingsJson Decision 绑定 JSON
     * @param triggerEventTypesJson 触发事件类型 JSON
     * @param kind                 规则类型
     * @param decisionStrategy     场景执行策略
     */
    public RuleVersionRow(Long ruleVersionId, String sceneCode, Long tenantId,
                          String conditionAstJson, String preGatesJson, String decisionBindingsJson,
                          String triggerEventTypesJson, String kind, String decisionStrategy) {
        this(ruleVersionId, sceneCode, tenantId, conditionAstJson, preGatesJson, decisionBindingsJson,
                triggerEventTypesJson, kind, decisionStrategy, null);
    }
}
