package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MetadataService 实现：为前端编辑器提供可用的 metric / conditionType / actionType 元数据。 */
@Service
@RequiredArgsConstructor
class MetadataServiceImpl implements MetadataService {

    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public MetadataResponse getSceneMetadata(String tenantId, String sceneCode) {
        SceneDef scene = sceneMapper.selectOne(
                new LambdaQueryWrapper<SceneDef>()
                        .eq(SceneDef::getTenantId, Long.valueOf(tenantId))
                        .eq(SceneDef::getCode, sceneCode));
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: " + sceneCode);
        }

        // v1 简化：查该租户下全部 ACTIVE metric，不过 scene_metric_binding 白名单
        List<MetricDefinition> metrics = metricDefinitionMapper.selectList(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getTenantId, Long.valueOf(tenantId))
                        .eq(MetricDefinition::getStatus, "ACTIVE"));

        List<MetricMeta> metricMetas = metrics.stream()
                .map(m -> new MetricMeta(m.getMetricCode(), m.getName(),
                        m.getDataType(), m.getSourceType(),
                        Boolean.TRUE.equals(m.getAllowProvided())))
                .toList();

        // conditionType / actionType 来自注册的 SPI Bean，v1 返回空列表
        return new MetadataResponse(List.of(), List.of(), metricMetas);
    }

    @Override
    public ProvidedMetricsResponse getProvidedMetrics(String tenantId, String sceneCode) {
        MetadataResponse all = getSceneMetadata(tenantId, sceneCode);
        List<MetricMeta> provided = all.availableMetrics().stream()
                .filter(MetricMeta::allowProvided)
                .toList();
        return new ProvidedMetricsResponse(provided);
    }

    @Override
    public List<MetricDescriptor> listMetricDefinitions(String tenantId, List<String> scenes) {
        // v1 简化：忽略 scenes 白名单，返回该租户全部 ACTIVE 定义（与 getSceneMetadata 口径一致）。
        // 仅 HTTP 模式 SDK 经此端点；scenes 已在 wire 契约里，未来收紧无需改 SDK——
        // 收紧路径：按 scenes 下已发布 rule_version 的 metricDependencies 并集过滤（不需 scene_metric_binding 表）。
        List<MetricDefinition> rows = metricDefinitionMapper.selectList(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getTenantId, Long.valueOf(tenantId))
                        .eq(MetricDefinition::getStatus, "ACTIVE"));
        return rows.stream().map(this::toDescriptor).toList();
    }

    private MetricDescriptor toDescriptor(MetricDefinition m) {
        // 把 dataType 一并塞进 params，供宿主 handler 结果强转使用（镜像 DbMetricDefinitionResolver）
        Map<String, Object> params = new HashMap<>(parseParams(m.getParams()));
        params.put("dataType", m.getDataType());
        return new MetricDescriptor(
                m.getMetricCode(), m.getSourceType(), m.getDataType(),
                Boolean.TRUE.equals(m.getAllowProvided()),
                m.getCacheTtlSeconds() == null ? 0 : m.getCacheTtlSeconds(),
                params);
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
