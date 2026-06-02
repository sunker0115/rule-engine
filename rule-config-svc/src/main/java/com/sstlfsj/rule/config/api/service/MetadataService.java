package com.sstlfsj.rule.config.api.service;

/** Provides scene metadata: available condition types, action types, and metrics. */
public interface MetadataService {

    /**
     * Returns the metadata for a scene: available condition types, action types, and metrics.
     *
     * @param tenantId  tenant owning the scene
     * @param sceneCode scene to query
     * @return metadata response containing condition/action/metric type lists
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
