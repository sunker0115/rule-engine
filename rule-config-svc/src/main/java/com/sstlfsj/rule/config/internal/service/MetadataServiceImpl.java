package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** MetadataService 实现：为前端编辑器提供可用的 metric / conditionType / actionType 元数据。 */
@Service
@RequiredArgsConstructor
class MetadataServiceImpl implements MetadataService {

    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;

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
}
