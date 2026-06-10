package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MetadataService 实现：为前端编辑器提供可用的 metric / conditionType / actionType 元数据。 */
@Service
@RequiredArgsConstructor
class MetadataServiceImpl implements MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataServiceImpl.class);

    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;

    @Override
    public MetadataResponse getSceneMetadata(String tenantId, String sceneCode) {
        SceneDef scene = sceneMapper.findByCode(Long.valueOf(tenantId), sceneCode);
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: " + sceneCode);
        }

        // metric 在 tenant 级对所有 scene 可用（无 scene 级绑定白名单，配置闭环 B 轮决策二）
        List<MetricDefinition> metrics = metricDefinitionMapper.findActiveByTenant(Long.valueOf(tenantId));

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

        // scenes 为空（FetchMode.ALL）：返回该租户全部 ACTIVE 定义（新规则只能绑 ACTIVE）
        if (scenes == null || scenes.isEmpty()) {
            return metricDefinitionMapper.findActiveByTenant(tid)
                    .stream().map(this::toDescriptor).toList();
        }

        // scenes 非空（FetchMode.DECLARED）：按被规则引用的精确 (code,version) 并集下发，含 SUPERSEDED。
        // 存量快照可能绑旧版（SUPERSEDED），若只下发 ACTIVE 版，评估期 resolve(code,oldVersion) 返回 null → 评估失败。
        Set<MetricDependency> deps = collectRequiredDeps(tid, scenes);
        if (deps.isEmpty()) return List.of();

        List<MetricDescriptor> result = new ArrayList<>();
        for (MetricDependency dep : deps) {
            MetricDefinition row = metricDefinitionMapper.findByCodeAndVersion(
                    tid, dep.metricCode(), dep.metricVersion());
            if (row != null) {
                result.add(toDescriptor(row));
            } else {
                // 规则绑了物理不存在的定义，属数据一致性异常；静默跳过会让 SDK 评估失败且无排障线索
                log.warn("metric 定义不存在，跳过下发: tenantId={}, code={}, version={}",
                        tid, dep.metricCode(), dep.metricVersion());
            }
        }
        return result;
    }

    /**
     * 取 scenes 下 ACTIVE rule_version 的 metricDependencies 并集，返回精确 (code,version) 对。
     * 与 collectRequiredMetricCodes 不同：保留 version，以便 DECLARED 分支按版本精确查询。
     */
    private Set<MetricDependency> collectRequiredDeps(Long tenantId, List<String> scenes) {
        List<Long> sceneIds = sceneMapper.findByCodes(tenantId, scenes)
                .stream().map(SceneDef::getId).toList();
        if (sceneIds.isEmpty()) return Set.of();

        List<Long> defIds = ruleDefinitionMapper.findByTenantAndSceneIds(tenantId, sceneIds)
                .stream().map(RuleDefinition::getId).toList();
        if (defIds.isEmpty()) return Set.of();

        Set<MetricDependency> deps = new HashSet<>();
        for (RuleVersion rv : ruleVersionMapper.findActiveByRuleDefIds(defIds)) {
            deps.addAll(rv.getMetricDependencies() != null ? rv.getMetricDependencies() : List.of());
        }
        return deps;
    }

    private MetricDescriptor toDescriptor(MetricDefinition m) {
        // 把 dataType 一并塞进 params，供宿主 handler 结果强转使用（镜像 DbMetricDefinitionResolver）
        Map<String, Object> params = new HashMap<>(m.getParams() != null ? m.getParams() : Map.of());
        params.put("dataType", m.getDataType());
        return new MetricDescriptor(
                m.getMetricCode(),
                m.getVersion() == null ? 1 : m.getVersion(),
                m.getSourceType(), m.getDataType(),
                Boolean.TRUE.equals(m.getAllowProvided()),
                m.getCacheTtlSeconds() == null ? 0 : m.getCacheTtlSeconds(),
                params);
    }

}
