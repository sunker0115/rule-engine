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
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则批量导出：按条件查规则集合，组装多规则自包含 Bundle（B7）。
 * <p>选取优先级 ruleIds → sceneId → 整租户；每条仅导当前 ACTIVE rule_version（无则跳过）。
 * scenes / metrics / decisions 跨规则去重。</p>
 */
@Service
@RequiredArgsConstructor
public class RuleExportService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;

    /** 按条件批量导出规则当前 ACTIVE 版本为 Bundle。 */
    @Transactional(readOnly = true)
    public RuleBundle export(String tenantIdStr, List<Long> ruleIds, Long sceneId) {
        Long tid = Long.valueOf(tenantIdStr);

        List<RuleDefinition> ruleDefs = ruleDefinitionMapper.selectForExport(tid, ruleIds, sceneId);

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
                        s.getEventTypes(), s.getPayloadSchema(), s.getDefaultParams(),
                        s.getPayloadSchemaVersion()))
                .toList();

        // 3. metrics（去重，精确版本）
        List<RuleBundle.MetricEntry> metricEntries = new ArrayList<>();
        for (MetricDependency dep : metricDeps) {
            MetricDefinition m = metricDefinitionMapper.findByCodeAndVersion(
                    tid, dep.metricCode(), dep.metricVersion());
            if (m != null) {
                metricEntries.add(new RuleBundle.MetricEntry(
                        m.getMetricCode(), m.getVersion(), m.getName(),
                        m.getSourceType(), m.getDataType(), m.getParams(),
                        m.getCacheTtlSeconds(), m.getAllowProvided()));
            }
        }

        // 4. decisions（去重）
        List<DecisionDefinition> decisions = decisionDefinitionMapper.findByCodes(tid, decisionCodes);
        List<RuleBundle.DecisionEntry> decisionEntries = decisions.stream()
                .map(d -> new RuleBundle.DecisionEntry(
                        d.getCode(), d.getName(), d.getPriority(), d.getDescription()))
                .toList();

        // 5. rules
        List<RuleBundle.RuleEntry> rules = new ArrayList<>();
        for (int i = 0; i < exportable.size(); i++) {
            RuleDefinition rd = exportable.get(i);
            RuleVersion rv = activeVersions.get(i);
            SceneDef scene = sceneById.get(rd.getSceneId());
            rules.add(new RuleBundle.RuleEntry(
                    rd.getCode(), rd.getName(),
                    (rv.getKind() != null ? rv.getKind() : RuleKind.AST_BOOLEAN).name(),
                    scene != null ? scene.getCode() : null,
                    rv.getConditionAst(), rv.getDecisionBindings(),
                    rv.getPreGates(), rv.getTriggerEventTypes(),
                    rv.getMetricDependencies() != null ? rv.getMetricDependencies() : List.of(),
                    rv.getPayloadDependencies() != null ? rv.getPayloadDependencies() : List.of()));
        }

        return new RuleBundle(1, Instant.now().toString(), tenantIdStr,
                rules, scenes, metricEntries, decisionEntries);
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
