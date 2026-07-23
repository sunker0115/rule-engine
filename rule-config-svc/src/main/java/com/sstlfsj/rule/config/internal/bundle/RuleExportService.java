package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则批量导出：按条件查规则集合，组装多规则自包含 Bundle v2。
 *
 * <p>选取优先级 ruleIds → sceneId → 整租户；每条仅导当前 ACTIVE rule_version（无则跳过）。
 * scenes / metrics / decisions 跨规则去重。
 * v2 新增：{@link RuleBundle.RuleEntry#script} 携带脚本源码；
 * {@link RuleBundle.RuleEntry#contentHash} 用于 import 幂等判断；
 * {@link RuleBundle#revision} 整 Bundle 内容摘要。</p>
 */
@Service
@RequiredArgsConstructor
public class RuleExportService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;
    private final ObjectMapper objectMapper;

    /** 按条件批量导出规则当前 ACTIVE 版本为 Bundle v2。 */
    @Transactional(readOnly = true)
    public RuleBundle export(Long tenantId, List<Long> ruleIds, Long sceneId) {
        List<RuleDefinition> ruleDefs = ruleDefinitionMapper.selectForExport(tenantId, ruleIds, sceneId);

        // 1. 逐条取 ACTIVE rule_version，无则跳过；同时收集 sceneId / metricDep / decisionCode
        List<RuleVersion> activeVersions = new ArrayList<>();
        List<RuleDefinition> exportable = new ArrayList<>();
        Set<Long> sceneIds = new LinkedHashSet<>();
        Set<MetricDependency> metricDeps = new LinkedHashSet<>();
        Set<String> decisionCodes = new LinkedHashSet<>();
        for (RuleDefinition rd : ruleDefs) {
            RuleVersion active = ruleVersionMapper.findActiveVersion(rd.getId());
            if (active == null) continue;
            exportable.add(rd);
            activeVersions.add(active);
            if (rd.getSceneId() != null) sceneIds.add(rd.getSceneId());
            metricDeps.addAll(active.getMetricDependencies() != null ? active.getMetricDependencies() : List.of());
            decisionCodes.addAll(parseDecisionCodes(active.getDecisionBindings()));
        }
        if (exportable.isEmpty()) {
            throw new IllegalArgumentException("无可导出的 ACTIVE 规则");
        }

        // 2. scenes（去重）+ sceneId → code 映射
        Map<Long, SceneDef> sceneById = new LinkedHashMap<>();
        for (SceneDef s : sceneMapper.findByIds(sceneIds)) {
            sceneById.put(s.getId(), s);
        }
        List<RuleBundle.SceneSnapshot> scenes = sceneById.values().stream()
                .map(s -> new RuleBundle.SceneSnapshot(
                        s.getCode(), s.getName(), s.getDescription(),
                        s.getSubjectType().name(), s.getDominantMode().name(), s.getDecisionStrategy().name(),
                        s.getEventTypes(), s.getPayloadSchema(), s.getDefaultParams()))
                .toList();

        // 3. metrics（去重，精确版本）
        List<RuleBundle.MetricEntry> metricEntries = new ArrayList<>();
        for (MetricDependency dep : metricDeps) {
            MetricDefinition m = metricDefinitionMapper.findByCodeAndVersion(
                    tenantId, dep.metricCode(), dep.metricVersion());
            if (m != null) {
                metricEntries.add(new RuleBundle.MetricEntry(
                        m.getMetricCode(), m.getVersion(), m.getName(),
                        m.getSourceType(), m.getDataType(), m.getParams(),
                        m.getCacheTtlSeconds(), m.getAllowProvided()));
            }
        }

        // 4. decisions（去重）
        List<DecisionDefinition> decisions = decisionDefinitionMapper.findByCodes(tenantId, decisionCodes);
        List<RuleBundle.DecisionEntry> decisionEntries = decisions.stream()
                .map(d -> new RuleBundle.DecisionEntry(
                        d.getCode(), d.getName(), d.getPriority(), d.getDescription()))
                .toList();

        // 5. rules（v2：携带 script + contentHash）
        List<RuleBundle.RuleEntry> rules = new ArrayList<>();
        for (int i = 0; i < exportable.size(); i++) {
            RuleDefinition rd = exportable.get(i);
            RuleVersion rv = activeVersions.get(i);
            SceneDef scene = sceneById.get(rd.getSceneId());
            String kindName = (rv.getKind() != null ? rv.getKind() : RuleKind.AST_BOOLEAN).name();
            String contentHash = RuleContentHasher.ruleHash(
                    rv.getBody(), rv.getDecisionBindings(), rv.getPreGates(),
                    kindName, rv.getTriggerEventTypes(), objectMapper);
            rules.add(new RuleBundle.RuleEntry(
                    rd.getCode(), rd.getName(), kindName,
                    scene != null ? scene.getCode() : null,
                    rv.getBody(), rv.getDecisionBindings(),
                    rv.getPreGates(), rv.getTriggerEventTypes(),
                    rv.getMetricDependencies() != null ? rv.getMetricDependencies() : List.of(),
                    rv.getPayloadDependencies() != null ? rv.getPayloadDependencies() : List.of(),
                    contentHash));
        }

        // 6. 先建不含 revision 的 bundle，再算整 bundle revision 写入
        RuleBundle bundleWithoutRevision = new RuleBundle(
                2, null, Instant.now().toString(), String.valueOf(tenantId),
                rules, scenes, metricEntries, decisionEntries);
        String revision = RuleContentHasher.bundleRevision(bundleWithoutRevision);
        return new RuleBundle(
                2, revision, bundleWithoutRevision.exportedAt(), bundleWithoutRevision.sourceTenant(),
                rules, scenes, metricEntries, decisionEntries);
    }

    /** 按条件导出规则当前 ACTIVE 版本为 RuleVersionSnapshot 列表（SDK 本地调用用）。 */
    @Transactional(readOnly = true)
    public List<RuleVersionSnapshot> exportSnapshots(Long tenantId, List<Long> ruleIds, Long sceneId) {
        List<RuleDefinition> ruleDefs = ruleDefinitionMapper.selectForExport(tenantId, ruleIds, sceneId);
        List<RuleVersionSnapshot> snapshots = new ArrayList<>();
        for (RuleDefinition rd : ruleDefs) {
            RuleVersion active = ruleVersionMapper.findActiveVersion(rd.getId());
            if (active == null) continue;
            SceneDef scene = sceneMapper.findByIds(Set.of(rd.getSceneId())).stream().findFirst().orElse(null);
            snapshots.add(new RuleVersionSnapshot(
                    active.getId(),
                    scene != null ? scene.getCode() : null,
                    String.valueOf(rd.getTenantId()),
                    active.getBody(),
                    active.getPreGates() != null ? active.getPreGates() : List.of(),
                    active.getDecisionBindings() != null ? active.getDecisionBindings() : List.of(),
                    active.getTriggerEventTypes() != null ? active.getTriggerEventTypes() : List.of(),
                    active.getKind() != null ? active.getKind().name() : RuleKind.AST_BOOLEAN.name(),
                    rd.getCode(),
                    active.getVersion() != null ? active.getVersion().longValue() : 0L,
                    active.getMetricDependencies() != null ? active.getMetricDependencies() : List.of(),
                    active.getPayloadDependencies() != null ? active.getPayloadDependencies() : List.of()));
        }
        if (snapshots.isEmpty()) throw new IllegalArgumentException("无可导出的 ACTIVE 规则");
        return snapshots;
    }

    private List<String> parseDecisionCodes(List<DecisionBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) return List.of();
        Set<String> codes = new LinkedHashSet<>();
        for (DecisionBinding b : bindings) {
            if (b.decisionCode() != null) codes.add(b.decisionCode());
        }
        return new ArrayList<>(codes);
    }
}
