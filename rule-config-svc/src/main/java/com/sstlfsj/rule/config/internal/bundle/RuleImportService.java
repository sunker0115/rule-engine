package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.ImportDiffReport;
import com.sstlfsj.rule.config.api.dto.ImportDiffReport.RuleImportConflict;
import com.sstlfsj.rule.config.api.dto.ImportDiffReport.RuleImportItem;
import com.sstlfsj.rule.config.api.dto.ImportPolicy;
import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.config.internal.domain.MetricEnums;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bundle v2 import：所有资源走完整 service 写链路（审计/事件/校验全部继承），
 * 支持 SKIP / OVERWRITE / ABORT 三种冲突策略，以及 dry-run 模式（事务内跑完强制回滚）。
 *
 * <h3>架构原则</h3>
 * <ul>
 *   <li>规则 import → {@link PublishService#createDraft}，内含 resolveAndValidate（script/metric 校验/依赖冻结）。</li>
 *   <li>scene/metric/decision → 对应 service write 方法，审计/事件自动继承。</li>
 *   <li>幂等：ACTIVE 版本 contentHash 一致 → 跳过，不建新版本。</li>
 *   <li>ABORT：collect-all 模式，全部规则跑完收集所有冲突；apply 时有冲突抛异常回滚，dry-run 返回含冲突的 report。</li>
 *   <li>dry-run：TransactionTemplate + setRollbackOnly 强制回滚，DB 不落任何数据。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RuleImportService {

    private final PublishService publishService;
    private final SceneService sceneService;
    private final MetricWriteService metricWriteService;
    private final DecisionService decisionService;
    // 直接读仅用于 hash 比较，不走写操作
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    /**
     * dry-run：在真实数据上跑完整 import 后强制回滚（DB 不落任何数据），返回的 diff 100% 准确反映真实 apply。
     *
     * <p>必须用编程式事务 + {@code setRollbackOnly}：不能用 {@code @Transactional} + 自抛自 catch 异常——
     * 异常被本方法 catch 后方法正常返回，Spring 认为无异常，事务照常提交不回滚（曾踩此坑）。</p>
     */
    public ImportDiffReport dryRun(Long tenantId, RuleBundle bundle, ImportPolicy policy, String actorId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            ImportDiffReport report = doImport(tenantId, bundle, policy, actorId);
            status.setRollbackOnly();
            return report;
        });
    }

    /**
     * 真实 apply：提交所有变更。ABORT 策略有冲突时抛 {@link ImportConflictException} 触发回滚。
     */
    @Transactional
    public ImportDiffReport apply(Long tenantId, RuleBundle bundle, ImportPolicy policy, String actorId) {
        ImportDiffReport report = doImport(tenantId, bundle, policy, actorId);
        ImportPolicy effective = policy != null ? policy : ImportPolicy.SKIP;
        if (effective == ImportPolicy.ABORT && !report.conflicts().isEmpty()) {
            throw new ImportConflictException(report);
        }
        return report;
    }

    // ---- 核心 import 逻辑 -----------------------------------------------

    private ImportDiffReport doImport(Long tenantId, RuleBundle bundle, ImportPolicy policy, String actorId) {
        if (bundle == null || bundle.rules() == null || bundle.rules().isEmpty()) {
            throw new IllegalArgumentException("Bundle 结构非法：rules 不得为空");
        }
        ImportPolicy effectivePolicy = policy != null ? policy : ImportPolicy.SKIP;

        // ① scenes
        int scenesCreated = upsertScenes(tenantId, bundle, actorId);

        // ② metrics（跳过项收集进 metricsSkipped 反馈调用方，区分"已存在"与"sourceType 不支持"）
        List<ImportDiffReport.MetricImportItem> metricsSkipped = new ArrayList<>();
        int metricsCreated = upsertMetrics(tenantId, bundle, actorId, metricsSkipped);

        // ③ decisions
        int decisionsCreated = upsertDecisions(tenantId, bundle, actorId);

        // ④ rules（走 createDraft，含 resolveAndValidate）
        List<RuleImportItem> willCreate = new ArrayList<>();
        List<RuleImportItem> willOverwrite = new ArrayList<>();
        List<RuleImportItem> skipped = new ArrayList<>();
        List<RuleImportConflict> conflicts = new ArrayList<>();

        for (RuleBundle.RuleEntry rule : bundle.rules()) {
            processRule(tenantId, rule, effectivePolicy, actorId,
                    willCreate, willOverwrite, skipped, conflicts);
        }

        // ABORT 是否中止由调用方 apply 决定——dry-run 需返回含冲突的 report 让前端展示，不在此抛异常
        return new ImportDiffReport(willCreate, willOverwrite, skipped, conflicts,
                scenesCreated, metricsCreated, metricsSkipped, decisionsCreated);
    }

    // ---- 规则逐条处理 -------------------------------------------------------

    private void processRule(Long tenantId, RuleBundle.RuleEntry rule, ImportPolicy policy,
                             String actorId,
                             List<RuleImportItem> willCreate, List<RuleImportItem> willOverwrite,
                             List<RuleImportItem> skipped, List<RuleImportConflict> conflicts) {

        // 解析 sceneId（scene 在 ① 已 upsert，这里直接查库）
        SceneDef scene = sceneMapper.findByCode(tenantId, rule.sceneCode());
        if (scene == null) {
            conflicts.add(new RuleImportConflict(rule.code(), rule.sceneCode(),
                    "SCENE_NOT_FOUND", "Scene 不在 Bundle 也不在目标环境"));
            return;
        }

        RuleDefinition existing = ruleDefinitionMapper.findBySceneAndCode(
                tenantId, scene.getId(), rule.code());

        // 幂等：比较 contentHash
        if (existing != null && rule.contentHash() != null) {
            RuleVersion activeVersion = ruleVersionMapper.findActiveVersion(existing.getId());
            if (activeVersion != null) {
                String targetHash = RuleContentHasher.ruleHash(
                        activeVersion.getConditionAst(), activeVersion.getDecisionBindings(),
                        activeVersion.getPreGates(),
                        (activeVersion.getKind() != null ? activeVersion.getKind().name() : "AST_BOOLEAN"),
                        activeVersion.getTriggerEventTypes(), activeVersion.getScriptSource(),
                        activeVersion.getFlowGraph(),
                        objectMapper);
                if (rule.contentHash().equals(targetHash)) {
                    skipped.add(new RuleImportItem(rule.code(), rule.sceneCode(), "内容 hash 一致，无需变更"));
                    return;
                }
            }
        }

        // 走完整 service 链（createDraft/editDraft/newVersion 均内含 resolveAndValidate）
        RuleContent content = new RuleContent(
                rule.name(), rule.kind(),
                rule.conditionAst(),
                rule.decisionBindings() != null ? rule.decisionBindings() : List.of(),
                rule.preGates() != null ? rule.preGates() : List.of(),
                rule.triggerEventTypes() != null ? rule.triggerEventTypes() : List.of(),
                rule.script(),
                rule.flowGraph());

        if (existing == null) {
            // 目标不存在 → 新建规则 + DRAFT
            publishService.createDraft(tenantId, rule.sceneCode(), rule.code(), content, actorId);
            willCreate.add(new RuleImportItem(rule.code(), rule.sceneCode(), "目标不存在，将新建"));
            return;
        }

        // 目标已存在且 hash 不同
        switch (policy) {
            case SKIP -> skipped.add(new RuleImportItem(rule.code(), rule.sceneCode(),
                    "SKIP 策略：目标已存在，保留现有版本"));
            case OVERWRITE -> {
                // 已存在规则不能用 createDraft（code 重复）：有 DRAFT 则原地 editDraft，否则基于 ACTIVE 建 newVersion
                RuleVersion draftVersion = ruleVersionMapper.findLatestDraft(existing.getId());
                if (draftVersion != null) {
                    publishService.editDraft(tenantId, existing.getId(), content, actorId);
                } else {
                    publishService.newVersion(tenantId, existing.getId(), content, null, actorId);
                }
                willOverwrite.add(new RuleImportItem(rule.code(), rule.sceneCode(),
                        draftVersion != null ? "原地更新现有 DRAFT" : "基于 ACTIVE 建新 DRAFT 版本"));
            }
            case ABORT -> conflicts.add(new RuleImportConflict(rule.code(), rule.sceneCode(),
                    "CONTENT_CHANGED", "目标已存在且内容不同，ABORT 策略下记为冲突"));
        }
    }

    // ---- scene/metric/decision upsert（走 service 写链，审计自动继承）----

    private int upsertScenes(Long tenantId, RuleBundle bundle, String actorId) {
        if (bundle.scenes() == null) return 0;
        // 批量预查已存在 code，避免逐条 findByCode 的 N 次往返
        List<String> codes = bundle.scenes().stream().map(RuleBundle.SceneSnapshot::code).toList();
        Set<String> existing = sceneMapper.findByCodes(tenantId, codes).stream()
                .map(SceneDef::getCode).collect(Collectors.toSet());
        int created = 0;
        for (RuleBundle.SceneSnapshot ss : bundle.scenes()) {
            if (existing.contains(ss.code())) continue;
            sceneService.createScene(tenantId, ss.code(), ss.name(),
                    ss.description() != null ? ss.description() : "",
                    ss.dominantMode(), ss.subjectType(),
                    ss.eventTypes() != null ? ss.eventTypes() : List.of(),
                    ss.payloadSchema() != null ? ss.payloadSchema() : List.of(),
                    ss.defaultParams(),
                    actorId);
            created++;
        }
        return created;
    }

    private int upsertMetrics(Long tenantId, RuleBundle bundle, String actorId,
                              List<ImportDiffReport.MetricImportItem> skipped) {
        if (bundle.metricDefinitions() == null) return 0;
        int created = 0;
        for (RuleBundle.MetricEntry me : bundle.metricDefinitions()) {
            if (metricDefinitionMapper.findAnyByCode(tenantId, me.metricCode()) != null) {
                skipped.add(new ImportDiffReport.MetricImportItem(me.metricCode(), "目标已存在，跳过"));
                continue;
            }
            // SQL_AGGREGATE / 非法枚举：不自动导入，记入 report 交人工处理
            if (!MetricEnums.DATA_TYPES.contains(me.dataType())
                    || !MetricEnums.SOURCE_TYPES.contains(me.sourceType())
                    || SourceType.SQL_AGGREGATE.equals(me.sourceType())) {
                skipped.add(new ImportDiffReport.MetricImportItem(me.metricCode(),
                        "sourceType=" + me.sourceType() + " 不支持自动导入，需人工处理"));
                continue;
            }
            var cmd = buildMetricCmd(me);
            metricWriteService.create(tenantId, me.metricCode(), cmd, actorId);
            created++;
        }
        return created;
    }

    private int upsertDecisions(Long tenantId, RuleBundle bundle, String actorId) {
        if (bundle.decisionDefinitions() == null) return 0;
        // 批量预查已存在 code，避免逐条 findByCode 的 N 次往返
        List<String> codes = bundle.decisionDefinitions().stream().map(RuleBundle.DecisionEntry::code).toList();
        Set<String> existing = decisionDefinitionMapper.findByCodes(tenantId, codes).stream()
                .map(DecisionDefinition::getCode).collect(Collectors.toSet());
        int created = 0;
        for (RuleBundle.DecisionEntry de : bundle.decisionDefinitions()) {
            if (existing.contains(de.code())) continue;
            decisionService.create(tenantId, de.code(), de.name(), de.priority(), de.description(), actorId);
            created++;
        }
        return created;
    }

    private MetricWriteCommand buildMetricCmd(RuleBundle.MetricEntry me) {
        return new MetricWriteCommand(
                me.name(), me.sourceType(), me.dataType(),
                me.params() != null ? me.params() : java.util.Map.of(),
                me.cacheTtlSeconds() != null ? me.cacheTtlSeconds() : 60,
                Boolean.TRUE.equals(me.allowProvided()),
                false);  // sensitive：bundle 导入默认非敏感，运营可后续自行修改
    }

    // ---- 内部异常（ABORT 策略）---------------------

    /** ABORT 策略冲突：携带 diff report，触发事务回滚，并被 controller 捕获为 422。 */
    public static class ImportConflictException extends RuntimeException {
        private final ImportDiffReport report;
        public ImportConflictException(ImportDiffReport report) {
            super("Import aborted: conflicts found");
            this.report = report;
        }
        public ImportDiffReport report() { return report; }
    }
}
