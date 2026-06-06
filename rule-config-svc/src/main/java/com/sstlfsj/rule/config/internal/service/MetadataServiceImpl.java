package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MetadataService 实现：为前端编辑器提供可用的 metric / conditionType / actionType 元数据。 */
@Service
@RequiredArgsConstructor
class MetadataServiceImpl implements MetadataService {

    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
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
        Long tid = Long.valueOf(tenantId);
        List<MetricDescriptor> all = metricDefinitionMapper.selectList(
                        new LambdaQueryWrapper<MetricDefinition>()
                                .eq(MetricDefinition::getTenantId, tid)
                                .eq(MetricDefinition::getStatus, "ACTIVE"))
                .stream().map(this::toDescriptor).toList();

        // scenes 为空（FetchMode.ALL）：返回该租户全部 ACTIVE 定义
        if (scenes == null || scenes.isEmpty()) {
            return all;
        }
        // scenes 非空（FetchMode.DECLARED）：仅返回这些 scenes 下 ACTIVE rule_version 的 metricDependencies 并集内的定义。
        // 口径与快照下发一致（rv.status=ACTIVE），保证 SDK 拿到的规则引用的 metric 定义都已下发，无遗漏。
        Set<String> required = collectRequiredMetricCodes(tid, scenes);
        return all.stream().filter(d -> required.contains(d.metricCode())).toList();
    }

    /** 取 scenes 下 ACTIVE rule_version 的 metricDependencies 并集（scene code → scene id → ruleDefinition id → ACTIVE 版本依赖）。 */
    private Set<String> collectRequiredMetricCodes(Long tenantId, List<String> scenes) {
        List<Long> sceneIds = sceneMapper.selectList(
                        new LambdaQueryWrapper<SceneDef>()
                                .eq(SceneDef::getTenantId, tenantId)
                                .in(SceneDef::getCode, scenes))
                .stream().map(SceneDef::getId).toList();
        if (sceneIds.isEmpty()) return Set.of();

        List<Long> defIds = ruleDefinitionMapper.selectList(
                        new LambdaQueryWrapper<RuleDefinition>()
                                .eq(RuleDefinition::getTenantId, tenantId)
                                .in(RuleDefinition::getSceneId, sceneIds))
                .stream().map(RuleDefinition::getId).toList();
        if (defIds.isEmpty()) return Set.of();

        Set<String> codes = new HashSet<>();
        for (RuleVersion rv : ruleVersionMapper.selectList(
                new LambdaQueryWrapper<RuleVersion>()
                        .in(RuleVersion::getRuleDefinitionId, defIds)
                        .eq(RuleVersion::getStatus, "ACTIVE"))) {
            codes.addAll(parseStringList(rv.getMetricDependencies()));
        }
        return codes;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
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
