package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricEnums;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则批量导入：幂等地把 Bundle 写入目标租户（B7）。
 * <p>单事务内先整体 upsert 依赖（Scene / metric / decision 缺失则建，已存在跳过），
 * 再逐条把规则落为 DRAFT 版本——已存在则追加草稿版本，不覆盖已发布版本。
 * SQL_AGGREGATE 类缺失 metric 不自动创建，列入待审清单。</p>
 */
@Service
@RequiredArgsConstructor
public class RuleImportService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 幂等批量导入 Bundle 到目标租户。 */
    @Transactional
    public RuleImportResult importBundle(String tenantIdStr, RuleBundle bundle, String actorId) {
        if (bundle == null || bundle.rules() == null || bundle.rules().isEmpty()) {
            throw new IllegalArgumentException("Bundle 结构非法：rules 不得为空");
        }
        Long tenantId = Long.valueOf(tenantIdStr);

        // 2. Scenes upsert + sceneCode → sceneId 映射
        List<String> scenesCreated = new ArrayList<>();
        List<String> scenesSkipped = new ArrayList<>();
        Map<String, Long> sceneIdByCode = new LinkedHashMap<>();
        if (bundle.scenes() != null) {
            for (RuleBundle.SceneSnapshot ss : bundle.scenes()) {
                SceneDef existing = sceneMapper.findByCode(tenantId, ss.code());
                if (existing != null) {
                    scenesSkipped.add(ss.code());
                    sceneIdByCode.put(ss.code(), existing.getId());
                    continue;
                }
                SceneDef s = new SceneDef();
                s.setTenantId(tenantId);
                s.setCode(ss.code());
                s.setName(ss.name());
                s.setDescription(ss.description());
                s.setSubjectType(ss.subjectType());
                s.setDominantMode(ss.dominantMode());
                s.setDecisionStrategy(ss.decisionStrategy());
                s.setEventTypes(ss.eventTypes());
                s.setPayloadSchema(ss.payloadSchema());
                s.setDefaultParams(ss.defaultParams());
                s.setPayloadSchemaVersion(ss.payloadSchemaVersion() == null ? 1 : ss.payloadSchemaVersion());
                s.setStatus("ACTIVE");
                s.setCreatedBy(actorId);
                s.setCreatedAt(LocalDateTime.now());
                sceneMapper.insert(s);
                scenesCreated.add(ss.code());
                sceneIdByCode.put(ss.code(), s.getId());
            }
        }

        // 3. Metrics upsert
        List<String> metricsCreated = new ArrayList<>();
        List<String> metricsSkipped = new ArrayList<>();
        List<String> metricsReview = new ArrayList<>();
        if (bundle.metricDefinitions() != null) {
            for (RuleBundle.MetricEntry me : bundle.metricDefinitions()) {
                MetricDefinition existing = metricDefinitionMapper.findAnyByCode(tenantId, me.metricCode());
                if (existing != null) {
                    metricsSkipped.add(me.metricCode());
                    continue;
                }
                // 非法 data_type/source_type 不自动创建(ENUM→VARCHAR 后 DB 不再约束,导入侧由此堵口),交人工 review
                if (!MetricEnums.DATA_TYPES.contains(me.dataType())
                        || !MetricEnums.SOURCE_TYPES.contains(me.sourceType())) {
                    metricsReview.add(me.metricCode());
                    continue;
                }
                if (SourceType.SQL_AGGREGATE.equals(me.sourceType())) {
                    // SQL 类参数含查询语句，需人工审核，不自动创建（发布期 metric 校验是安全网）
                    metricsReview.add(me.metricCode());
                    continue;
                }
                MetricDefinition m = new MetricDefinition();
                m.setTenantId(tenantId);
                m.setMetricCode(me.metricCode());
                m.setVersion(me.version() == null ? 1 : me.version());
                m.setName(me.name());
                m.setSourceType(me.sourceType());
                m.setDataType(me.dataType());
                m.setParams(me.params() != null ? me.params() : java.util.Map.of());
                m.setCacheTtlSeconds(me.cacheTtlSeconds() == null ? 60 : me.cacheTtlSeconds());
                m.setAllowProvided(Boolean.TRUE.equals(me.allowProvided()));
                m.setStatus("ACTIVE");
                m.setCreatedBy(actorId);
                m.setCreatedAt(LocalDateTime.now());
                metricDefinitionMapper.insert(m);
                metricsCreated.add(me.metricCode());
            }
        }

        // 4. Decisions upsert
        List<String> decisionsCreated = new ArrayList<>();
        List<String> decisionsSkipped = new ArrayList<>();
        if (bundle.decisionDefinitions() != null) {
            for (RuleBundle.DecisionEntry de : bundle.decisionDefinitions()) {
                DecisionDefinition existing = decisionDefinitionMapper.findByCode(tenantId, de.code());
                if (existing != null) {
                    decisionsSkipped.add(de.code());
                    continue;
                }
                DecisionDefinition d = new DecisionDefinition();
                d.setTenantId(tenantId);
                d.setCode(de.code());
                d.setName(de.name());
                d.setPriority(de.priority());
                d.setDescription(de.description());
                d.setActions(de.actions() == null ? List.of() : de.actions());
                d.setStatus("ACTIVE");
                d.setCreatedBy(actorId);
                d.setCreatedAt(LocalDateTime.now());
                decisionDefinitionMapper.insert(d);
                decisionsCreated.add(de.code());
            }
        }

        // 5. Rules 逐条
        List<RuleImportResult.ImportedRule> importedRules = new ArrayList<>();
        for (RuleBundle.RuleEntry rule : bundle.rules()) {
            Long sceneId = resolveSceneId(tenantId, rule.sceneCode(), sceneIdByCode);
            String kind = (rule.kind() == null || rule.kind().isBlank()) ? RuleKind.AST_BOOLEAN.tag() : rule.kind();

            RuleDefinition rd = ruleDefinitionMapper.findBySceneAndCode(tenantId, sceneId, rule.code());
            boolean ruleExisted = rd != null;
            long newVersion;
            if (rd == null) {
                rd = new RuleDefinition();
                rd.setTenantId(tenantId);
                rd.setSceneId(sceneId);
                rd.setCode(rule.code());
                rd.setName(rule.name());
                rd.setStatus("DRAFT");
                rd.setKind(kind);
                rd.setCreatedBy(actorId);
                rd.setCreatedAt(LocalDateTime.now());
                ruleDefinitionMapper.insert(rd);
                newVersion = 1L;
            } else {
                // 已存在：追加草稿版本，不动 rule_definition 状态/currentVersion（不覆盖已发布版本）
                newVersion = ruleVersionMapper.maxVersion(rd.getId()) + 1;
            }

            RuleVersion rv = new RuleVersion();
            rv.setRuleDefinitionId(rd.getId());
            rv.setVersion(newVersion);
            rv.setConditionAst(rule.conditionAst() != null ? rule.conditionAst()
                    : new com.sstlfsj.rule.kernel.api.model.ast.AndNode(java.util.List.of(), null, null));
            rv.setDecisionBindings(rule.decisionBindings() != null ? rule.decisionBindings() : java.util.List.of());
            rv.setPreGates(rule.preGates() != null ? rule.preGates() : java.util.List.of());
            rv.setKind(kind);
            rv.setTriggerEventTypes(rule.triggerEventTypes() != null ? rule.triggerEventTypes() : java.util.List.of());
            rv.setMetricDependencies(rule.metricDependencies() != null ? rule.metricDependencies() : java.util.List.of());
            rv.setStatus("DRAFT");
            rv.setCreatedAt(LocalDateTime.now());
            ruleVersionMapper.insert(rv);

            eventPublisher.publishEvent(new OperationAuditedEvent(
                    tenantId, actorId, "USER", "IMPORT", "rule_definition", rd.getId().toString(),
                    null,
                    "{\"ruleVersionId\":" + rv.getId() + ",\"version\":" + newVersion
                            + ",\"ruleExisted\":" + ruleExisted + "}",
                    LocalDateTime.now()));

            importedRules.add(new RuleImportResult.ImportedRule(
                    rd.getId(), rv.getId(), newVersion, rule.code(), rule.sceneCode(), ruleExisted));
        }

        return new RuleImportResult(importedRules,
                scenesCreated, scenesSkipped,
                metricsCreated, metricsSkipped, metricsReview,
                decisionsCreated, decisionsSkipped,
                bundle.actionTypeManifest() == null ? List.of() : bundle.actionTypeManifest());
    }

    /** 按 sceneCode 解析 sceneId：优先本次 upsert 映射，缺失则兜底查库，仍无则报错。 */
    private Long resolveSceneId(Long tenantId, String sceneCode, Map<String, Long> sceneIdByCode) {
        Long id = sceneIdByCode.get(sceneCode);
        if (id != null) return id;
        SceneDef scene = sceneMapper.findByCode(tenantId, sceneCode);
        if (scene == null) {
            throw new IllegalArgumentException("规则引用的 Scene 不在 Bundle 也不在目标环境: code=" + sceneCode);
        }
        sceneIdByCode.put(sceneCode, scene.getId());
        return scene.getId();
    }
}
