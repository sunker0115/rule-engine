package com.sstlfsj.rule.config.api.service;

/** 场景元数据查询：可用条件类型、动作类型及指标列表。 */
public interface MetadataService {

    /**
     * 返回指定场景的元数据，包括可用条件类型、动作类型和指标列表。
     *
     * @param tenantId  场景所属租户 ID
     * @param sceneCode 待查询的场景编码
     * @return 包含条件/动作/指标类型列表的元数据响应
     */
    MetadataResponse getSceneMetadata(String tenantId, String sceneCode);

    record MetadataResponse(
            java.util.List<ConditionTypeMeta> conditionTypes,
            java.util.List<ActionTypeMeta> actionTypes,
            java.util.List<MetricMeta> availableMetrics
    ) {}

    record ConditionTypeMeta(String code, String displayName,
                              Object paramsSchema, boolean requiresMetric) {}
    record ActionTypeMeta(String code, String displayName,
                          Object paramsSchema, boolean compensatable) {}
    record MetricMeta(String metricCode, String name,
                      String dataType, String sourceType, boolean allowProvided) {}
}
