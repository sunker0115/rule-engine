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
        String metricDependenciesJson,
        /** rule_version.payload_dependencies JSON 数组字符串；可能为 null（旧行容错）。 */
        String payloadDependenciesJson,
        /** rule_definition.code 逻辑编码。 */
        String code,
        /** rule_version.version 版本号。 */
        long version,
        /** rule_version.script_source JSON(ScriptSource {source,lang});非脚本规则为 null。 */
        String scriptSourceJson
) {
    /**
     * 兼容旧调用点的便利构造（无 metricDependenciesJson / payloadDependenciesJson，均默认 null）。
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
                triggerEventTypesJson, kind, decisionStrategy, null, null, null, 0L);
    }

    /**
     * 兼容旧 13 参调用点(无 scriptSourceJson,默认 null)。
     *
     * @param ruleVersionId         规则版本 id
     * @param sceneCode             场景编码
     * @param tenantId              租户 id
     * @param conditionAstJson      条件 AST JSON
     * @param preGatesJson          Pre-Gate JSON
     * @param decisionBindingsJson  Decision 绑定 JSON
     * @param triggerEventTypesJson 触发事件类型 JSON
     * @param kind                  规则类型
     * @param decisionStrategy      场景执行策略
     * @param metricDependenciesJson metric 依赖 JSON
     * @param payloadDependenciesJson payload 依赖 JSON
     * @param code                  逻辑编码
     * @param version               版本号
     */
    public RuleVersionRow(Long ruleVersionId, String sceneCode, Long tenantId,
                          String conditionAstJson, String preGatesJson, String decisionBindingsJson,
                          String triggerEventTypesJson, String kind, String decisionStrategy,
                          String metricDependenciesJson, String payloadDependenciesJson,
                          String code, long version) {
        this(ruleVersionId, sceneCode, tenantId, conditionAstJson, preGatesJson, decisionBindingsJson,
                triggerEventTypesJson, kind, decisionStrategy, metricDependenciesJson, payloadDependenciesJson,
                code, version, null);
    }
}
