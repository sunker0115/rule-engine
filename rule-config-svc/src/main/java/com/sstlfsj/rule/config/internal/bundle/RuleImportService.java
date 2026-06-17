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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Bundle v2 import：所有资源走完整 service 写链路（审计/事件/校验全部继承），
 * 支持 SKIP / OVERWRITE / ABORT 三种冲突策略，以及 dry-run 模式（事务内跑完强制回滚）。
 *
 * <h3>架构原则</h3>
 * <ul>
 *   <li>规则 import → {@link PublishService#createDraft}，内含 resolveAndValidate（script/metric 校验/依赖冻结）。</li>
 *   <li>scene/metric/decision → 对应 service write 方法，审计/事件自动继承。</li>
 *   <li>幂等：ACTIVE 版本 contentHash 一致 → 跳过，不建新版本。</li>
 *   <li>ABORT：collect-all 模式，全部规则跑完后有冲突整体抛出，事务自然回滚。</li>
 *   <li>dry-run：事务内完整执行，收集 diff 后抛 {@link DryRunCompletedException} 强制回滚。</li>
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

    /**
     * dry-run：事务内跑完整 import 逻辑，收集 diff 后强制回滚（DB 不落任何数据）。
     * 返回的 {@link ImportDiffReport} 100% 准确反映真实 apply 的效果。
     */
    @Transactional
    public ImportDiffReport dryRun(Long tenantId, RuleBundle bundle, ImportPolicy policy, String actorId) {
        try {
            ImportDiffReport report = doImport(tenantId, bundle, policy, actorId);
            throw new DryRunCompletedException(report);
        } catch (DryRunCompletedException e) {
            return e.report;
        }
        // Spring @Transactional 在 DryRunCompletedException（RuntimeException）触发回滚
    }

    /**
     * 真实 apply：提交所有变更。
     */
    @Transactional
    public ImportDiffReport apply(Long tenantId, RuleBundle bundle, ImportPolicy policy, String actorId) {
        return doImport(tenantId, bundle, policy, actorId);
    }

    // ---- 核心 import 逻辑 -----------------------------------------------

    private ImportDiffReport doImport(Long tenantId, RuleBundle bundle, ImportPolicy policy, String actorId) {
        if (bundle == null || bundle.rules() == null || bundle.rules().isEmpty()) {
            throw new IllegalArgumentException("Bundle 结构非法：rules 不得为空");
        }
        ImportPolicy effectivePolicy = policy != null ? policy : ImportPolicy.SKIP;

        // ① scenes
        int scenesCreated = upsertScenes(tenantId, bundle, actorId);

        // ② metrics
        int metricsCreated = upsertMetrics(tenantId, bundle, actorId);

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

        if (effectivePolicy == ImportPolicy.ABORT && !conflicts.isEmpty()) {
            throw new ImportConflictException(
                    new ImportDiffReport(willCreate, willOverwrite, skipped, conflicts,
                            scenesCreated, metricsCreated, decisionsCreated));
        }

        return new ImportDiffReport(willCreate, willOverwrite, skipped, conflicts,
                scenesCreated, metricsCreated, decisionsCreated);
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
                        objectMapper);
                if (rule.contentHash().equals(targetHash)) {
                    skipped.add(new RuleImportItem(rule.code(), rule.sceneCode(), "内容 hash 一致，无需变更"));
                    return;
                }
            }
        }

        if (existing != null) {
            // 目标已存在且 hash 不同
            switch (policy) {
                case SKIP -> {
                    skipped.add(new RuleImportItem(rule.code(), rule.sceneCode(),
                            "SKIP 策略：目标已存在，保留现有版本"));
                    return;
                }
                case OVERWRITE -> {
                    // 清掉旧 DRAFT，再建新 DRAFT
                    RuleVersion draftVersion = ruleVersionMapper.findLatestDraft(existing.getId());
                    if (draftVersion != null) {
                        publishService.deleteDraftVersion(tenantId, existing.getId(), draftVersion.getId(), actorId);
                    }
                    // 继续往下 createDraft
                }
                case ABORT -> {
                    conflicts.add(new RuleImportConflict(rule.code(), rule.sceneCode(),
                            "CONTENT_CHANGED", "目标已存在且内容不同，ABORT 策略下记为冲突"));
                    return;
                }
            }
        }

        // createDraft：走完整 service 链（resolveAndValidate 在内）
        RuleContent content = new RuleContent(
                rule.name(), rule.kind(),
                rule.conditionAst(),
                rule.decisionBindings() != null ? rule.decisionBindings() : List.of(),
                rule.preGates() != null ? rule.preGates() : List.of(),
                rule.triggerEventTypes() != null ? rule.triggerEventTypes() : List.of(),
                rule.script());

        publishService.createDraft(tenantId, rule.sceneCode(), rule.code(), content, actorId);

        if (existing == null) {
            willCreate.add(new RuleImportItem(rule.code(), rule.sceneCode(), "目标不存在，将新建"));
        } else {
            willOverwrite.add(new RuleImportItem(rule.code(), rule.sceneCode(),
                    "已清除旧 DRAFT，新建替换版本"));
        }
    }

    // ---- scene/metric/decision upsert（走 service 写链，审计自动继承）----

    private int upsertScenes(Long tenantId, RuleBundle bundle, String actorId) {
        if (bundle.scenes() == null) return 0;
        int created = 0;
        for (RuleBundle.SceneSnapshot ss : bundle.scenes()) {
            if (sceneMapper.findByCode(tenantId, ss.code()) != null) continue;
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

    private int upsertMetrics(Long tenantId, RuleBundle bundle, String actorId) {
        if (bundle.metricDefinitions() == null) return 0;
        int created = 0;
        for (RuleBundle.MetricEntry me : bundle.metricDefinitions()) {
            if (metricDefinitionMapper.findAnyByCode(tenantId, me.metricCode()) != null) continue;
            // SQL_AGGREGATE / 非法枚举：跳过，交人工处理
            if (!MetricEnums.DATA_TYPES.contains(me.dataType())
                    || !MetricEnums.SOURCE_TYPES.contains(me.sourceType())
                    || SourceType.SQL_AGGREGATE.equals(me.sourceType())) {
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
        int created = 0;
        for (RuleBundle.DecisionEntry de : bundle.decisionDefinitions()) {
            if (decisionDefinitionMapper.findByCode(tenantId, de.code()) != null) continue;
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

    // ---- 内部异常（用于 dry-run 强制回滚 + ABORT 策略）---------------------

    /** dry-run 完成信号：携带 diff report，触发 @Transactional 回滚。 */
    static class DryRunCompletedException extends RuntimeException {
        final ImportDiffReport report;
        DryRunCompletedException(ImportDiffReport report) {
            super("dry-run completed");
            this.report = report;
        }
    }

    /** ABORT 策略冲突：携带 diff report，触发事务回滚，并被 controller 捕获为 400。 */
    public static class ImportConflictException extends RuntimeException {
        private final ImportDiffReport report;
        public ImportConflictException(ImportDiffReport report) {
            super("Import aborted: conflicts found");
            this.report = report;
        }
        public ImportDiffReport report() { return report; }
    }
}
