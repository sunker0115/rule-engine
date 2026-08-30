package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.internal.event.DraftCreatedSnapshot;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.event.RulePublishedSnapshot;
import com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.model.flow.FlowEdge;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.FlowNode;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode;
import com.sstlfsj.rule.kernel.api.model.flow.SwitchNode;
import com.sstlfsj.rule.kernel.api.model.flow.TransformNode;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ScriptTypeEnv;
import com.sstlfsj.rule.kernel.internal.analysis.FlowCycleDetector;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规则发布核心流程。
 * <p>
 * 事务边界：整个发布流程在一个本地事务内完成（原地 UPDATE rule_version 把最新 DRAFT 翻 ACTIVE
 * + markSuperseded 旧 ACTIVE + UPDATE rule_definition + INSERT audit_log），事务提交后发布 Modulith 事件。
 * </p>
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final SceneMapper sceneMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;
    /** lang → 表达式引擎，发布期脚本语法校验 + referencedVariables 冻依赖用；镜像 eval-svc ScriptExecutor 装配。 */
    private final Map<String, ExpressionEngine> expressionEngines;

    /**
     * 已注册取数资源名目录（由 eval-svc 提供）；纯 config 部署时为 null，资源名校验跳过。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sstlfsj.rule.config.api.spi.MetricResourceCatalog metricResourceCatalog;

    /**
     * @param ruleDefinitionMapper     规则定义 mapper
     * @param sceneMapper              场景 mapper
     * @param ruleVersionMapper        规则版本 mapper
     * @param eventPublisher           Spring 事件发布器
     * @param metricDefinitionMapper   metric 定义 mapper
     * @param decisionDefinitionMapper decision 定义 mapper
     * @param expressionEngines        已注册表达式引擎（Spring 收集，未 opt-in 引擎时为空），按 lang 建路由 map
     */
    public PublishService(RuleDefinitionMapper ruleDefinitionMapper, SceneMapper sceneMapper,
                          RuleVersionMapper ruleVersionMapper, ApplicationEventPublisher eventPublisher,
                          MetricDefinitionMapper metricDefinitionMapper,
                          DecisionDefinitionMapper decisionDefinitionMapper,
                          List<ExpressionEngine> expressionEngines) {
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.sceneMapper = sceneMapper;
        this.ruleVersionMapper = ruleVersionMapper;
        this.eventPublisher = eventPublisher;
        this.metricDefinitionMapper = metricDefinitionMapper;
        this.decisionDefinitionMapper = decisionDefinitionMapper;
        Map<String, ExpressionEngine> byLang = new HashMap<>();
        if (expressionEngines != null) {
            for (ExpressionEngine e : expressionEngines) {
                if (byLang.putIfAbsent(e.lang(), e) != null) {
                    throw new IllegalStateException("多个 ExpressionEngine 声明同一 lang=" + e.lang());
                }
            }
        }
        this.expressionEngines = byLang;
    }

    /**
     * 发布规则：把最新 DRAFT 版本原地激活（DRAFT→ACTIVE），不增版本、不重解析。
     * <p>
     * 草稿在 create/edit/newVersion 时已跑 resolveAndValidate 冻结为完整快照（premise A），
     * 故发布仅做状态翻转：激活最新 DRAFT 行 + supersede 旧 ACTIVE + 更新 rule_definition 指向。
     * 不要求规则当前为 DRAFT 状态（已发布规则出新版本后仍 PUBLISHED 但有新 DRAFT 待发布），
     * 仅要求存在待发布的 DRAFT 版本。
     * </p>
     *
     * @param tenantId         租户 id
     * @param ruleDefinitionId 规则定义 id
     * @param actorId          操作人（来自 X-Actor-Id header）
     * @return 被激活版本的 RuleVersionSnapshot（作为 publish API 响应；含 id/conditionAst/metricDeps/payloadDeps，
     *         decisionBindings/preGates/triggerEventTypes 不在响应携带，需经 GET 规则详情查询。
     *         eval-svc 倒排索引热更由 RulePublishedEvent 从 DB 重载触发，不依赖此返回值）
     */
    @Transactional
    public RuleVersionSnapshot publish(Long tenantId, Long ruleDefinitionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        // DISABLED 规则须先 enable 再发布，否则可通过 publish 路径绕过 transitionStatus 状态机
        if (rule.getStatus() == RuleDefinitionStatus.DISABLED) {
            throw new IllegalArgumentException("DISABLED 规则不允许直接发布，需先启用(enable)后再发布");
        }
        SceneDef scene = sceneMapper.findByCode(rule.getTenantId(), rule.getSceneCode());
        if (scene == null) {
            throw new IllegalStateException("Scene 不存在: code=" + rule.getSceneCode());
        }
        // 加载最新 DRAFT；无待发布草稿则拒
        RuleVersion draft = ruleVersionMapper.findLatestDraft(ruleDefinitionId);
        if (draft == null) {
            throw new IllegalStateException("没有待发布的草稿版本，请先保存规则草稿");
        }

        // DECISION_FLOW：模板实例化时 strictRefs=false 跳过了引用冻结，发布时需重解析以冻快照并级联发布子规则
        if (draft.getKind() == RuleKind.DECISION_FLOW && draft.getBody() instanceof FlowBody fb) {
            ResolvedDraft resolved = resolveAndValidate(
                    tenantId, scene, RuleKind.DECISION_FLOW,
                    null, draft.getDecisionBindings(), draft.getPreGates(),
                    draft.getTriggerEventTypes(), null, fb.flowGraph(), true);
            RuleVersion reFrozen = buildDraftVersion(ruleDefinitionId, draft.getVersion(), resolved);
            draft.setBody(reFrozen.getBody());
            draft.setDecisionBindings(reFrozen.getDecisionBindings());
            draft.setPreGates(reFrozen.getPreGates());
            draft.setTriggerEventTypes(reFrozen.getTriggerEventTypes());
            draft.setMetricDependencies(reFrozen.getMetricDependencies());
            draft.setPayloadDependencies(reFrozen.getPayloadDependencies());
            ruleVersionMapper.updateById(draft);
        }

        Long previousActiveId = rule.getCurrentVersion();

        // 原地激活 DRAFT 行（不增版本）
        draft.setStatus(RuleVersionStatus.ACTIVE);
        draft.setPublishedBy(actorId);
        draft.setPublishedAt(LocalDateTime.now());
        ruleVersionMapper.updateById(draft);

        if (previousActiveId != null) {
            ruleVersionMapper.markSuperseded(previousActiveId);
        }
        RuleStatusSnapshot beforeSnap = new RuleStatusSnapshot(
                ruleDefinitionId, rule.getStatus().name(), previousActiveId);
        rule.setStatus(RuleDefinitionStatus.PUBLISHED);
        rule.setCurrentVersion(draft.getId());
        rule.setPublishedBy(actorId);
        rule.setPublishedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.PUBLISH, AuditTargetType.RULE_DEFINITION, ruleDefinitionId.toString(),
                beforeSnap, new RulePublishedSnapshot(draft.getId(), draft.getVersion()), LocalDateTime.now()));

        // kind 取被发布的 draft 行（premise A 冻结的权威值），抗"定义级 kind 与版本漂移"
        RuleKind kind = draft.getKind() != null ? draft.getKind() : RuleKind.AST_BOOLEAN;
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                draft.getId(), scene.getCode(), String.valueOf(tenantId),
                draft.getBody(), List.of(), List.of(), List.of(),
                kind.name(), rule.getCode(), draft.getVersion(),
                draft.getMetricDependencies(), draft.getPayloadDependencies());
        eventPublisher.publishEvent(new RulePublishedEvent(
                String.valueOf(tenantId), scene.getCode(), draft.getId()));
        return snapshot;
    }

    /**
     * 原地更新该规则最新 DRAFT 版本内容（不增版本），按当前世界重跑 resolveAndValidate 冻结快照。
     * <p>
     * 仅最新 DRAFT 行可改：editDraft 不产生新版本号，落库的仍是同一行（version 不变）。
     * 规则的 name/kind/updatedBy/updatedAt 一并更新；发 UPDATE 审计事件。
     * </p>
     *
     * @param tenantId         租户 id
     * @param ruleDefinitionId 规则定义 id
     * @param content          规则内容（name null/空白时不改；kind null 时回退草稿现有 kind 或 AST_BOOLEAN；
     *                         conditionAst null 兜底为空 AndNode；decisionBindings/preGates/triggerEventTypes null 视为空）
     * @param actorId          操作人
     * @return 被更新草稿的 id 与版本信息（version 不变）
     */
    @Transactional
    public DraftCreatedResult editDraft(Long tenantId, Long ruleDefinitionId, RuleContent content, String actorId) {
        String name = content.name();
        RuleKind kind = parseKind(content.kind());
        RuleBody body = content.body();
        AstNode conditionAst = body instanceof AstBody ab ? ab.conditionAst() : null;
        List<RuleVersionSnapshot.DecisionBinding> decisionBindings = content.decisionBindings();
        List<RuleVersionSnapshot.PreGateConfig> preGates = content.preGates();
        List<String> triggerEventTypes = content.triggerEventTypes();
        ScriptSource script = body instanceof ScriptBody sb ? sb.script() : null;
        FlowGraph flowGraph = body instanceof FlowBody fb ? fb.flowGraph() : null;

        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        SceneDef scene = sceneMapper.findByCode(rule.getTenantId(), rule.getSceneCode());
        if (scene == null) {
            throw new IllegalStateException("Scene 不存在: code=" + rule.getSceneCode());
        }
        RuleVersion draft = ruleVersionMapper.findLatestDraft(ruleDefinitionId);
        if (draft == null) {
            throw new IllegalStateException("没有可编辑的草稿版本");
        }

        // kind 省略时回退到草稿现有 kind（原地编辑不应静默把已有 SCORECARD/TREE/TABLE 重置为 AST_BOOLEAN）
        RuleKind effectiveKind = kind != null ? kind
                : (draft.getKind() != null ? draft.getKind() : RuleKind.AST_BOOLEAN);
        validateKindBodyConsistent(effectiveKind, body);
        ResolvedDraft resolved = resolveAndValidate(
                tenantId, scene, effectiveKind, conditionAst, decisionBindings, preGates, triggerEventTypes,
                script, flowGraph);

        // 原地更新 DRAFT 行内容（version 不变）
        draft.setBody(toBody(resolved));
        draft.setDecisionBindings(resolved.decisionBindings());
        draft.setPreGates(resolved.preGates());
        draft.setKind(effectiveKind);
        draft.setTriggerEventTypes(resolved.triggerEventTypes());
        draft.setMetricDependencies(resolved.metricDeps());
        draft.setPayloadDependencies(resolved.payloadDeps());
        ruleVersionMapper.updateById(draft);

        if (name != null && !name.isBlank()) {
            rule.setName(name);
        }
        rule.setKind(effectiveKind);
        rule.setUpdatedBy(actorId);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        // 草稿编辑不做内容级 diff 审计：before/after 同一快照仅标识被改的草稿(ruleId+versionId)，
        // 不还原编辑前内容(草稿是可反复改的中间态，内容历史不进审计；线上变更审计在 publish 处)
        DraftCreatedSnapshot snap = new DraftCreatedSnapshot(rule.getId(), draft.getId());
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.UPDATE, AuditTargetType.RULE_DEFINITION, rule.getId().toString(),
                snap, snap, LocalDateTime.now()));
        return new DraftCreatedResult(rule.getId(), draft.getId(), draft.getVersion(), RuleDefinitionStatus.DRAFT.name());
    }

    /**
     * 给已发布规则出新版本草稿（v_max+1, DRAFT）。要求当前无未发布 DRAFT（一条规则同时一条 DRAFT）。
     * <p>
     * fromVersionId 非空时为"回退"：草稿内容克隆自该版本（ast/bindings/preGates/triggers + kind，
     * 忽略入参的 body 内容字段），按当前世界重跑 resolveAndValidate（metric 须仍 ACTIVE 等）；
     * 产出的是 DRAFT，激活仍走显式 publish。
     * </p>
     *
     * @param tenantId          租户 id
     * @param ruleDefinitionId  规则定义 id
     * @param content           规则内容（name null/空白时不改；kind null 时兜底规则现有 kind 或 AST_BOOLEAN；
     *                          fromVersionId 非空时 conditionAst/decisionBindings/preGates/triggerEventTypes/script 内容字段忽略，改用克隆值）
     * @param fromVersionId     回退源版本 id，非空时克隆其内容；null 时按入参建新草稿
     * @param actorId           操作人
     * @return 新建草稿的 id 与版本信息（version = v_max+1）
     */
    @Transactional
    public DraftCreatedResult newVersion(Long tenantId, Long ruleDefinitionId, RuleContent content,
            Long fromVersionId, String actorId) {
        String name = content.name();
        RuleKind kind = parseKind(content.kind());
        RuleBody body = content.body();
        AstNode conditionAst = body instanceof AstBody ab ? ab.conditionAst() : null;
        List<RuleVersionSnapshot.DecisionBinding> decisionBindings = content.decisionBindings();
        List<RuleVersionSnapshot.PreGateConfig> preGates = content.preGates();
        List<String> triggerEventTypes = content.triggerEventTypes();
        ScriptSource script = body instanceof ScriptBody sb ? sb.script() : null;
        FlowGraph flowGraph = body instanceof FlowBody fb ? fb.flowGraph() : null;

        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        SceneDef scene = sceneMapper.findByCode(rule.getTenantId(), rule.getSceneCode());
        if (scene == null) {
            throw new IllegalStateException("Scene 不存在: code=" + rule.getSceneCode());
        }
        // 一条规则同时只允许一条待发布 DRAFT：有未发布草稿则拒绝出新版本
        if (ruleVersionMapper.findLatestDraft(ruleDefinitionId) != null) {
            throw new IllegalArgumentException("规则已有待发布草稿，请先发布或删除后再出新版本");
        }

        RuleKind effectiveKind = kind != null ? kind
                : (rule.getKind() != null ? rule.getKind() : RuleKind.AST_BOOLEAN);
        if (fromVersionId == null) validateKindBodyConsistent(effectiveKind, body);
        AstNode srcAst = conditionAst;
        List<RuleVersionSnapshot.DecisionBinding> srcBindings = decisionBindings;
        List<RuleVersionSnapshot.PreGateConfig> srcGates = preGates;
        List<String> srcTriggers = triggerEventTypes;
        ScriptSource srcScript = script;
        FlowGraph srcFlow = flowGraph;
        if (fromVersionId != null) {
            // 回退：克隆旧版本内容（忽略入参 body 内容字段），按当前世界重解析
            RuleVersion from = ruleVersionMapper.findByIdAndRule(fromVersionId, ruleDefinitionId);
            if (from == null) {
                throw new IllegalArgumentException("回退源版本不存在: versionId=" + fromVersionId);
            }
            RuleBody fromBody = from.getBody();
            srcAst = fromBody instanceof AstBody ab ? ab.conditionAst() : null;
            srcBindings = from.getDecisionBindings();
            srcGates = from.getPreGates();
            srcTriggers = from.getTriggerEventTypes();
            srcScript = fromBody instanceof ScriptBody sb ? sb.script() : null;
            srcFlow = fromBody instanceof FlowBody fb ? fb.flowGraph() : null;
            effectiveKind = from.getKind() != null ? from.getKind() : effectiveKind;
        }

        ResolvedDraft resolved = resolveAndValidate(
                tenantId, scene, effectiveKind, srcAst, srcBindings, srcGates, srcTriggers, srcScript, srcFlow);
        long version = ruleVersionMapper.maxVersion(ruleDefinitionId) + 1;
        RuleVersion rv = buildDraftVersion(ruleDefinitionId, version, resolved);
        ruleVersionMapper.insert(rv);

        if (name != null && !name.isBlank()) {
            rule.setName(name);
        }
        rule.setKind(effectiveKind);
        rule.setUpdatedBy(actorId);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        DraftCreatedSnapshot snap = new DraftCreatedSnapshot(rule.getId(), rv.getId());
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.CREATE, AuditTargetType.RULE_DEFINITION, rule.getId().toString(),
                snap, snap, LocalDateTime.now()));
        return new DraftCreatedResult(rule.getId(), rv.getId(), version, RuleDefinitionStatus.DRAFT.name());
    }

    /**
     * 删整条未发布规则：仅当从未发布过（无 ACTIVE/SUPERSEDED 版本）→ 级联删 rule_definition + 全部 rule_version。
     * <p>
     * 已发布过的规则（存在非 DRAFT 版本）一律拒删——线上引用完整性红线，请改用禁用。
     * </p>
     *
     * @param tenantId         租户 id
     * @param ruleDefinitionId 规则定义 id
     * @param actorId          操作人
     */
    @Transactional
    public void deleteRule(Long tenantId, Long ruleDefinitionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        if (ruleVersionMapper.hasNonDraftVersion(ruleDefinitionId)) {
            throw new IllegalArgumentException("规则已发布过（存在 ACTIVE/SUPERSEDED 版本），不可删除；请改用禁用");
        }
        RuleStatusSnapshot snap = new RuleStatusSnapshot(
                ruleDefinitionId, rule.getStatus().name(), rule.getCurrentVersion());
        ruleVersionMapper.deleteByRuleDefinitionId(ruleDefinitionId);
        ruleDefinitionMapper.deleteById(ruleDefinitionId);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.DELETE, AuditTargetType.RULE_DEFINITION, ruleDefinitionId.toString(),
                snap, snap, LocalDateTime.now()));
    }

    /**
     * 删单个待发布草稿版本：仅当该 version 是 DRAFT → 删那条 rule_version（线上 ACTIVE/SUPERSEDED 不动）。
     *
     * @param tenantId         租户 id
     * @param ruleDefinitionId 规则定义 id
     * @param versionId        待删版本 id（须归属该规则）
     * @param actorId          操作人
     */
    @Transactional
    public void deleteDraftVersion(Long tenantId, Long ruleDefinitionId, Long versionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        RuleVersion version = ruleVersionMapper.findByIdAndRule(versionId, ruleDefinitionId);
        if (version == null) {
            throw new IllegalArgumentException("版本不存在: versionId=" + versionId);
        }
        if (version.getStatus() != RuleVersionStatus.DRAFT) {
            throw new IllegalArgumentException("只能删除 DRAFT 版本，当前状态: " + version.getStatus());
        }
        ruleVersionMapper.deleteById(versionId);
        DraftCreatedSnapshot snap = new DraftCreatedSnapshot(ruleDefinitionId, versionId);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.DELETE, AuditTargetType.RULE_VERSION, versionId.toString(),
                snap, snap, LocalDateTime.now()));
    }

    /**
     * 草稿解析+校验产出：已冻结的 rule_version 内容字段（resolvedAst 含 dataType、
     * metricDeps/payloadDeps 已冻、decisionBindings 含 name、triggerEventTypes/preGates 规整）。
     */
    public record ResolvedDraft(
            RuleKind kind,
            AstNode resolvedAst,
            List<RuleVersionSnapshot.DecisionBinding> decisionBindings,
            List<RuleVersionSnapshot.PreGateConfig> preGates,
            List<String> triggerEventTypes,
            List<MetricDependency> metricDeps,
            List<PayloadDependency> payloadDeps,
            ScriptSource scriptSource,
            FlowGraph flowGraph,
            Map<String, RuleVersionSnapshot> referencedSnapshots) {
    }

    /**
     * 解析+校验草稿输入，产出完整冻结的版本内容（premise A）。供 createDraft/editDraft/newVersion 调用。
     * 任一校验不过抛 IllegalArgumentException（→ 400）。
     *
     * @param tenantId          租户 id
     * @param scene             所属场景（已加载）
     * @param kind              规则类型（非空）
     * @param conditionAst      草稿条件 AST，null 兜底为空 AndNode
     * @param rawBindings       草稿决策绑定（仅 decisionCode + 占位 priority），null 视为空
     * @param preGates          前置门控，null 视为空
     * @param triggerEventTypes 触发事件类型，null 视为空
     * @param script            EXPRESSION_SCRIPT 脚本载体，其它 kind 传 null
     * @param flowGraph         DECISION_FLOW 决策图，其它 kind 传 null
     * @return 冻结后的版本内容
     */
    public ResolvedDraft resolveAndValidate(
            Long tenantId, SceneDef scene, RuleKind kind,
            AstNode conditionAst,
            List<RuleVersionSnapshot.DecisionBinding> rawBindings,
            List<RuleVersionSnapshot.PreGateConfig> preGates,
            List<String> triggerEventTypes,
            ScriptSource script,
            FlowGraph flowGraph) {
        return resolveAndValidate(tenantId, scene, kind, conditionAst, rawBindings, preGates,
                triggerEventTypes, script, flowGraph, true);
    }

    /** resolveAndValidate 重载：strictRefs=false 时跳过 DECISION_FLOW 的 RuleRef 冻结（模板实例化使用）。 */
    public ResolvedDraft resolveAndValidate(
            Long tenantId, SceneDef scene, RuleKind kind,
            AstNode conditionAst,
            List<RuleVersionSnapshot.DecisionBinding> rawBindings,
            List<RuleVersionSnapshot.PreGateConfig> preGates,
            List<String> triggerEventTypes,
            ScriptSource script,
            FlowGraph flowGraph,
            boolean strictRefs) {

        AstNode ast = conditionAst != null ? conditionAst
                : new AndNode(List.of(), null, null);
        List<RuleVersionSnapshot.DecisionBinding> bindings = rawBindings != null ? rawBindings : List.of();
        List<RuleVersionSnapshot.PreGateConfig> gates = preGates != null ? preGates : List.of();
        List<String> triggers = triggerEventTypes != null ? triggerEventTypes : List.of();

        String kindTag = kind.name();
        java.util.Set<String> validKinds = java.util.Set.of(
                RuleKind.AST_BOOLEAN.tag(), RuleKind.SCORECARD.tag(),
                RuleKind.DECISION_TREE.tag(), RuleKind.DECISION_TABLE.tag(),
                RuleKind.EXPRESSION_SCRIPT.tag(), RuleKind.DECISION_FLOW.tag());
        if (!validKinds.contains(kindTag)) {
            throw new IllegalArgumentException("不支持的规则 kind: " + kindTag);
        }
        // EXPRESSION_SCRIPT 命中即提前 return：脚本不进 AST，走引擎无关层（compile + refVars 冻依赖）
        if (RuleKind.EXPRESSION_SCRIPT.tag().equals(kindTag)) {
            return resolveScriptDraft(tenantId, scene, script, bindings, gates, triggers);
        }
        // DECISION_FLOW 命中即提前 return：图不进 AST，走图编排层；strictRefs=false 时跳过 RuleRef 冻结
        if (RuleKind.DECISION_FLOW.tag().equals(kindTag)) {
            return resolveFlowDraft(tenantId, scene, flowGraph, bindings, gates, triggers, strictRefs);
        }
        // 结构校验：SCORECARD 根/权重、DECISION_TREE 结构、DECISION_TABLE 行列一致
        validateKindStructure(kindTag, ast);

        // param 键校验：遍历 AST 校每个 ConditionNode 的必填 param 键齐全
        ConditionParamValidator.validate(ast);

        validateTriggerEventTypes(triggers, scene.getEventTypes());
        validatePreGateParams(gates);

        // metric 收集 + ACTIVE 冻结 + 安全校验
        List<String> metricCodes = MetricDependencyCollector.collect(ast);
        Map<String, String> dataTypeMap = new HashMap<>();
        List<MetricDependency> metricDeps = freezeMetricDeps(tenantId, metricCodes, dataTypeMap);

        // payload 收集 + scene.payloadSchema 声明校验 + 冻结依赖
        List<String> payloadFields = PayloadFieldCollector.collect(ast);
        Map<String, String> payloadTypeMap = new HashMap<>();
        List<PayloadDependency> payloadDeps = freezePayloadDeps(scene, payloadFields, payloadTypeMap);

        AstNode resolvedAst = (metricCodes.isEmpty() && payloadFields.isEmpty())
                ? ast : AstDataTypeResolver.resolve(ast, dataTypeMap, payloadTypeMap);

        // SCORECARD bands 直接回填 name/priority（不走 decisionBindings 搬运）
        if (RuleKind.SCORECARD.tag().equals(kindTag) && resolvedAst instanceof ScorecardRootNode scRoot
                && !scRoot.bands().isEmpty()) {
            List<ScoreBand> enrichedBands = enrichBands(tenantId, scRoot.bands());
            resolvedAst = new ScorecardRootNode(scRoot.conditions(), scRoot.threshold(), enrichedBands);
        }

        // SCORECARD 的 band decisionCode 已在 enrichBands 回填进 ScoreBand，不再注入 decisionBindings；
        // 非 SCORECARD 的 AST 不含 bands，bindings 原样冻结即可
        List<RuleVersionSnapshot.DecisionBinding> frozenBindings =
                freezeDecisionBindings(tenantId, scene, bindings);

        return new ResolvedDraft(kind, resolvedAst, frozenBindings, gates, triggers, metricDeps, payloadDeps,
                null, null, Map.of());
    }

    /**
     * EXPRESSION_SCRIPT 分支：引擎无关层校验。语法 compile + referencedVariables 冻依赖 + typed 类型检查，
     * conditionAst=null。typeCheck 按被引用变量声明类型捕获 string 参与数值比较等类型不符(强引擎)。
     *
     * @param tenantId 租户 id
     * @param scene    所属场景
     * @param script   脚本载体（非空、source 非空白）
     * @param bindings 规整后的决策绑定
     * @param gates    规整后的前置门控
     * @param triggers 规整后的触发事件类型
     * @return 冻结后的脚本规则版本内容（resolvedAst=null，script 原样冻入）
     */
    private ResolvedDraft resolveScriptDraft(Long tenantId, SceneDef scene, ScriptSource script,
            List<RuleVersionSnapshot.DecisionBinding> bindings,
            List<RuleVersionSnapshot.PreGateConfig> gates, List<String> triggers) {
        if (script == null || script.source() == null || script.source().isBlank()) {
            throw new IllegalArgumentException("EXPRESSION_SCRIPT 规则必须提供非空脚本");
        }
        ExpressionEngine engine = expressionEngines.get(script.lang());
        if (engine == null) {
            throw new IllegalArgumentException("无对应表达式引擎,lang=" + script.lang());
        }
        java.util.Set<String> refVars;
        try {
            // 语法/编译失败抛 ExpressionCompileException
            refVars = engine.compile(script.source()).referencedVariables();
        } catch (com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException e) {
            throw new IllegalArgumentException("脚本编译失败: " + e.getMessage(), e);
        }
        // 校验顺序对齐 AST 路径：先 trigger/preGate 校验，再冻 metric/payload 依赖（同款输入报错顺序一致）
        validateTriggerEventTypes(triggers, scene.getEventTypes());
        validatePreGateParams(gates);

        // referencedVariables 形如 metrics.x / payload.y / subject.z；按前缀拆（subject.* 开放，不校验/不冻）
        List<String> metricCodes = stripPrefix(refVars, "metrics.");
        List<String> payloadFields = stripPrefix(refVars, "payload.");
        Map<String, String> metricTypeMap = new HashMap<>();
        Map<String, String> payloadTypeMap = new HashMap<>();
        List<MetricDependency> metricDeps = freezeMetricDeps(tenantId, metricCodes, metricTypeMap);
        List<PayloadDependency> payloadDeps = freezePayloadDeps(scene, payloadFields, payloadTypeMap);

        // typed 类型检查：按被引用变量的声明类型构造 env，引擎(强类型则)捕获 string 参与数值比较等类型不符
        try {
            engine.typeCheck(script.source(), new ScriptTypeEnv(
                    toDataTypeMap(metricTypeMap), toDataTypeMap(payloadTypeMap)));
        } catch (com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException e) {
            throw new IllegalArgumentException("脚本类型检查失败: " + e.getMessage(), e);
        }

        List<RuleVersionSnapshot.DecisionBinding> frozenBindings = freezeDecisionBindings(tenantId, scene, bindings);
        // resolvedAst=null：脚本规则不进 AST；script 原样冻入
        return new ResolvedDraft(RuleKind.EXPRESSION_SCRIPT, null, frozenBindings, gates, triggers,
                metricDeps, payloadDeps, script, null, Map.of());
    }

    /**
     * DECISION_FLOW 分支：图编排层校验+冻结。结构校验（入口/孤儿/Switch caseKey/RuleRef 可解析/Output 存在）→
     * 遍历 RuleRefNode 按 ruleCode 冻被引规则 ACTIVE 快照（同 Scene + 有 ACTIVE，否则拒）→
     * OutputNode.decisionCode 仿 freezeDecisionBindings 校验存在并回填 name/priority 冻进 decisionBindings →
     * metricDependencies = 被引快照 metricDeps 并集(按 code 去重) + Switch/Transform 表达式引用 metric；
     * payloadDependencies = Switch/Transform 表达式引用的 payload.* 字段(仿脚本 kind 校验 scene.payloadSchema 声明并冻结)。
     * conditionAst=null、script=null、flowGraph 原样冻入。
     * <p>
     * flow 表达式仅做 compile 语法校验，<b>不做 typeCheck 强类型检查</b>（区别于脚本 kind）：flow 图的类型环需
     * 按节点顺序逐步构建（Transform 产出的 flow.* 变量类型依赖上游），比脚本单表达式复杂，v1 backlog 不做。
     * </p>
     *
     * @param tenantId 租户 id
     * @param scene    所属场景
     * @param flow     决策图（非空、nodes 非空）
     * @param bindings 规整后的入参决策绑定（flow kind 忽略，决策面由 OutputNode 定义）
     * @param gates    规整后的前置门控
     * @param triggers 规整后的触发事件类型
     * @return 冻结后的 flow 规则版本内容（resolvedAst=null，flowGraph/referencedSnapshots 冻入）
     */
    private ResolvedDraft resolveFlowDraft(Long tenantId, SceneDef scene, FlowGraph flow,
            List<RuleVersionSnapshot.DecisionBinding> bindings,
            List<RuleVersionSnapshot.PreGateConfig> gates, List<String> triggers,
            boolean strictRefs) {
        if (flow == null || flow.nodes().isEmpty()) {
            throw new IllegalArgumentException("DECISION_FLOW 规则必须提供非空决策图");
        }
        validateFlowStructure(flow);
        // 环检测前置：成环使发布期无法定序（运行期虽有 visited 兜底，仍属配置错误），拒收
        List<String> cycle = FlowCycleDetector.findCycle(flow);
        if (!cycle.isEmpty()) {
            throw new IllegalArgumentException("DECISION_FLOW 决策图存在环，无法发布: "
                    + String.join(" -> ", cycle) + " -> " + cycle.getFirst());
        }
        // 校验顺序对齐 AST/脚本路径：先 trigger/preGate，再冻依赖
        validateTriggerEventTypes(triggers, scene.getEventTypes());
        validatePreGateParams(gates);

        // RuleRef 冻结：按 ruleCode 查被引规则 ACTIVE 版本；strictRefs=false 时跳过（模板实例化场景，发布时再冻）
        Map<String, RuleVersionSnapshot> referenced = new LinkedHashMap<>();
        if (strictRefs) {
            for (FlowNode node : flow.nodes()) {
                if (node instanceof RuleRefNode ref) {
                    referenced.computeIfAbsent(ref.ruleCode(), code -> freezeReferencedRule(tenantId, code));
                }
            }
        }

        // Switch/Transform 表达式引用变量：同一编译分流 metrics.* 与 payload.*（编译兼作语法校验）
        MetricDependencyCollector.FlowExpressionRefs exprRefs =
                MetricDependencyCollector.collectFlowExpressionRefs(flow, expressionEngines);

        // metricDeps 并集：被引快照冻结 deps(按 code 去重,继承其冻结版本) + Switch/Transform 表达式引用 metric(冻当前 ACTIVE)
        LinkedHashMap<String, MetricDependency> unionByCode = new LinkedHashMap<>();
        for (RuleVersionSnapshot snap : referenced.values()) {
            for (MetricDependency md : snap.metricDependencies()) {
                unionByCode.putIfAbsent(md.metricCode(), md);
            }
        }
        // flow v1 不做类型检查，freezeMetricDeps 的 dataType 出参(供 AST 路径类型环)在此不使用，传空 map 丢弃
        List<MetricDependency> exprDeps = freezeMetricDeps(tenantId, exprRefs.metricCodes(), new HashMap<>());
        for (MetricDependency md : exprDeps) {
            unionByCode.putIfAbsent(md.metricCode(), md);
        }
        List<MetricDependency> metricDeps = new ArrayList<>(unionByCode.values());

        // payload 依赖：Switch/Transform 表达式引用的 payload.* 字段，仿脚本 kind 校验 scene.payloadSchema 声明 + 冻结
        // (undeclared 抛 UNRESOLVED_VARIABLE；被引规则自身的 payloadDeps 在其快照内，不并入 flow)
        // flow v1 不做类型检查，freezePayloadDeps 的 dataType 出参(供 AST 路径类型环)在此不使用，传空 map 丢弃
        List<PayloadDependency> payloadDeps = freezePayloadDeps(scene, exprRefs.payloadFields(), new HashMap<>());

        // Output 决策冻结：收集 OutputNode.decisionCode；strictRefs=false 时跳过（模板实例化发布时再校验）
        List<String> outputCodes = flow.nodes().stream()
                .filter(OutputNode.class::isInstance).map(n -> ((OutputNode) n).decisionCode())
                .filter(dc -> dc != null && !dc.isBlank()).distinct().toList();
        List<RuleVersionSnapshot.DecisionBinding> flowDecisionBindings;
        if (strictRefs) {
            List<RuleVersionSnapshot.DecisionBinding> rawOutputBindings = outputCodes.stream()
                    .map(dc -> new RuleVersionSnapshot.DecisionBinding(dc, 0)).toList();
            flowDecisionBindings = freezeDecisionBindings(tenantId, scene, rawOutputBindings);
        } else {
            flowDecisionBindings = List.of();
        }

        // flow 决策面由 OutputNode 定义，入参 bindings 忽略（v1）；resolvedAst/script=null，flow 原样冻入
        return new ResolvedDraft(RuleKind.DECISION_FLOW, null, flowDecisionBindings, gates, triggers,
                metricDeps, payloadDeps, null, flow, referenced);
    }

    /**
     * 冻结一条被 DECISION_FLOW RuleRefNode 引用的规则：按 tenant 级 ruleCode 查对应规则的 ACTIVE 版本
     * （允许跨 Scene 引用），用其 typed 字段组装完整 {@link RuleVersionSnapshot}（发布期定格）。
     * 规则不存在/无 ACTIVE 版本均拒绝发布。快照 sceneCode 取被引规则自身的 sceneCode。
     *
     * @param tenantId 租户 id
     * @param ruleCode 被引规则逻辑编码（tenant 内唯一，可属任意 Scene）
     * @return 冻结的被引规则完整快照
     */
    private RuleVersionSnapshot freezeReferencedRule(Long tenantId, String ruleCode) {
        if (ruleCode == null || ruleCode.isBlank()) {
            throw new IllegalArgumentException("DECISION_FLOW RuleRefNode.ruleCode 不得为空");
        }
        // 按 (tenant, code) 查：ruleCode 为 tenant 级业务标识，允许跨 Scene 引用
        RuleDefinition ref = ruleDefinitionMapper.findByTenantAndCode(tenantId, ruleCode);
        if (ref == null) {
            throw new IllegalArgumentException("DECISION_FLOW 引用的规则不存在: " + ruleCode);
        }
        RuleVersion active = ruleVersionMapper.findActiveVersion(ref.getId());
        if (active == null) {
            // 无 ACTIVE → 检查是否 DRAFT，有则级联自动发布
            RuleVersion draft = ruleVersionMapper.findLatestDraft(ref.getId());
            if (draft != null) {
                log.info("Flow 发布：级联自动发布被引规则 {} (id={})", ruleCode, ref.getId());
                publish(tenantId, ref.getId(), "system");  // 发布后产生 ACTIVE 版本
                active = ruleVersionMapper.findActiveVersion(ref.getId());
            }
            if (active == null) {
                throw new IllegalArgumentException(
                    "DECISION_FLOW 引用的规则无 ACTIVE 版本且无 DRAFT 可自动发布: " + ruleCode);
            }
        }
        RuleKind refKind = active.getKind() != null ? active.getKind() : RuleKind.AST_BOOLEAN;
        // 直接由 typed 实体字段组装完整快照（同 SnapshotAssembler 的形状，但源为实体而非 JSON，保全 payloadDeps 等全字段）；
        // 被引若为 flow，其 referencedSnapshots 在它自己发布时已冻，此处原样携带，不递归重冻
        return new RuleVersionSnapshot(
                active.getId(), ref.getSceneCode(), String.valueOf(tenantId),
                active.getBody(), active.getPreGates(), active.getDecisionBindings(),
                active.getTriggerEventTypes(), refKind.name(), ref.getCode(), active.getVersion(),
                active.getMetricDependencies(), active.getPayloadDependencies());
    }

    /**
     * DECISION_FLOW 结构校验（不做环检测/可达性，那属 P4 静态分析）：节点 id 唯一非空、
     * Switch/Transform 有 lang+expression、inputNodeId 指向存在节点、边端点均存在、
     * Switch 出边 caseKey ⊆ 该 Switch.caseKeys（default 边 caseKey=null 允许）、无孤儿节点（非入口节点须被边触达）。
     */
    private static void validateFlowStructure(FlowGraph flow) {
        Map<String, FlowNode> byId = new HashMap<>();
        for (FlowNode n : flow.nodes()) {
            if (n.id() == null || n.id().isBlank()) {
                throw new IllegalArgumentException("DECISION_FLOW 节点 id 不得为空");
            }
            if (byId.putIfAbsent(n.id(), n) != null) {
                throw new IllegalArgumentException("DECISION_FLOW 节点 id 重复: " + n.id());
            }
            switch (n) {
                case SwitchNode sw -> {
                    if (sw.lang() == null || sw.expression() == null || sw.expression().isBlank()) {
                        throw new IllegalArgumentException("DECISION_FLOW SwitchNode 须有 lang 与非空 expression: " + sw.id());
                    }
                }
                case TransformNode tf -> {
                    if (tf.lang() == null || tf.expression() == null || tf.expression().isBlank()) {
                        throw new IllegalArgumentException("DECISION_FLOW TransformNode 须有 lang 与非空 expression: " + tf.id());
                    }
                }
                default -> { /* RuleRefNode.ruleCode/OutputNode.decisionCode 由冻结步校验 */ }
            }
        }
        String input = flow.inputNodeId();
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("DECISION_FLOW 缺少 inputNodeId");
        }
        if (!byId.containsKey(input)) {
            throw new IllegalArgumentException("DECISION_FLOW inputNodeId 指向不存在的节点: " + input);
        }
        Set<String> touched = new HashSet<>();
        for (FlowEdge e : flow.edges()) {
            if (!byId.containsKey(e.from())) {
                throw new IllegalArgumentException("DECISION_FLOW 边的 from 指向不存在的节点: " + e.from());
            }
            if (!byId.containsKey(e.to())) {
                throw new IllegalArgumentException("DECISION_FLOW 边的 to 指向不存在的节点: " + e.to());
            }
            touched.add(e.from());
            touched.add(e.to());
            // Switch 出边非 default 的 caseKey 须属该 Switch 的合法分支键集
            if (byId.get(e.from()) instanceof SwitchNode sw && e.caseKey() != null
                    && !sw.caseKeys().contains(e.caseKey())) {
                throw new IllegalArgumentException(
                        "DECISION_FLOW Switch 出边 caseKey 不在 caseKeys 内: " + e.caseKey() + " (node=" + sw.id() + ")");
            }
        }
        for (FlowNode n : flow.nodes()) {
            if (!n.id().equals(input) && !touched.contains(n.id())) {
                throw new IllegalArgumentException("DECISION_FLOW 存在孤儿节点(无边连接): " + n.id());
            }
        }
    }

    /** 从点路径集合按前缀（如 "metrics."）过滤并去前缀，去重保序。 */
    private static List<String> stripPrefix(java.util.Set<String> refVars, String prefix) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String v : refVars) {
            if (v.startsWith(prefix)) out.add(v.substring(prefix.length()));
        }
        return new ArrayList<>(out);
    }

    /** dataType tag map（field→tag）转 DataType enum map，供 typed 类型检查环境构造。 */
    private static Map<String, DataType> toDataTypeMap(Map<String, String> tagMap) {
        Map<String, DataType> out = HashMap.newHashMap(tagMap.size());
        tagMap.forEach((k, tag) -> out.put(k, DataType.fromTag(tag)));
        return out;
    }

    /**
     * 按 metricCode 列表查 ACTIVE 定义,冻结 (code, version) 依赖,并产出 code→dataType 映射 + 安全校验。
     *
     * @param tenantId    租户 id
     * @param metricCodes 被引用的 metric code(去重)
     * @param dataTypeMap [出参] 填充 code→dataType(供 AST 路径 AstDataTypeResolver 用;script 路径可忽略)
     * @return 冻结的 metric 依赖
     */
    private List<MetricDependency> freezeMetricDeps(Long tenantId, List<String> metricCodes,
                                                    Map<String, String> dataTypeMap) {
        List<MetricDependency> metricDeps = new ArrayList<>();
        if (metricCodes.isEmpty()) return metricDeps;
        List<MetricDefinition> metricDefs = metricDefinitionMapper.findActiveByCodes(tenantId, metricCodes);
        Map<String, MetricDefinition> activeByCode = new HashMap<>();
        for (MetricDefinition m : metricDefs) {
            if (activeByCode.putIfAbsent(m.getMetricCode(), m) != null) {
                throw new IllegalArgumentException("metric 存在多个 ACTIVE 版本，数据异常: " + m.getMetricCode());
            }
        }
        for (String code : metricCodes) {
            MetricDefinition m = activeByCode.get(code);
            if (m == null) throw new IllegalArgumentException("被引用的 metric 无 ACTIVE 版本: " + code);
            metricDeps.add(new MetricDependency(code, m.getVersion() == null ? 1 : m.getVersion()));
        }
        dataTypeMap.putAll(activeByCode.values().stream()
                .collect(Collectors.toMap(MetricDefinition::getMetricCode, MetricDefinition::getDataType)));
        java.util.Set<String> dsNames = metricResourceCatalog != null ? metricResourceCatalog.datasourceNames() : null;
        java.util.Set<String> connectorNames = metricResourceCatalog != null ? metricResourceCatalog.connectorNames(tenantId) : null;
        new MetricSafetyValidator().validate(new ArrayList<>(activeByCode.values()), dsNames, connectorNames);
        return metricDeps;
    }

    /**
     * 按 payload 字段列表查 scene.payloadSchema 声明,冻结依赖,并产出 field→dataType 映射。
     *
     * @param scene          所属场景
     * @param payloadFields  被引用的 payload 字段(去重)
     * @param payloadTypeMap [出参] 填充 field→dataType
     * @return 冻结的 payload 依赖
     */
    private List<PayloadDependency> freezePayloadDeps(SceneDef scene, List<String> payloadFields,
                                                      Map<String, String> payloadTypeMap) {
        List<PayloadDependency> payloadDeps = new ArrayList<>();
        if (payloadFields.isEmpty()) return payloadDeps;
        List<PayloadFieldSpec> schema = scene.getPayloadSchema() != null ? scene.getPayloadSchema() : List.of();
        Map<String, PayloadFieldSpec> specByName = new HashMap<>();
        for (PayloadFieldSpec f : schema) specByName.put(f.name(), f);
        for (String field : payloadFields) {
            PayloadFieldSpec spec = specByName.get(field);
            if (spec == null) {
                throw new IllegalArgumentException(
                        "UNRESOLVED_VARIABLE: 规则引用的 payload 字段未在 scene.payloadSchema 声明: " + field);
            }
            String dataTypeTag = PayloadDataTypeMapper.toDataTypeTag(spec.type());
            payloadTypeMap.put(field, dataTypeTag);
            payloadDeps.add(PayloadDependency.builder()
                    .name(field).dataType(dataTypeTag).required(spec.required())
                    .enumValues(spec.enumValues()).minimum(spec.minimum())
                    .maximum(spec.maximum()).pattern(spec.pattern())
                    .build());
        }
        return payloadDeps;
    }

    /** kind 结构校验（从 publish 抽取，逻辑原样）。 */
    private void validateKindStructure(String kindTag, AstNode ast) {
        if (RuleKind.SCORECARD.tag().equals(kindTag)) {
            if (!(ast instanceof ScorecardRootNode scorecardRoot)) {
                throw new IllegalArgumentException("kind=SCORECARD 的规则 conditionAst 根节点必须是 ScorecardRootNode");
            }
            for (ConditionNode leaf : scorecardRoot.conditions()) {
                if (leaf.weight() == null || leaf.weight() <= 0) {
                    throw new IllegalArgumentException("SCORECARD 条件节点 weight 必须 > 0，conditionType=" + leaf.conditionType());
                }
            }
            // bands 非空时：每段 min<max；按 minScore 排序后相邻段左闭右开端点相接不算重叠
            List<ScoreBand> bands = scorecardRoot.bands();
            if (!bands.isEmpty()) {
                List<ScoreBand> sorted = bands.stream()
                        .sorted(Comparator.comparingDouble(ScoreBand::minScore)).toList();
                for (ScoreBand b : sorted) {
                    if (b.minScore() >= b.maxScore()) {
                        throw new IllegalArgumentException(
                                "SCORECARD band minScore 必须 < maxScore: [" + b.minScore() + "," + b.maxScore() + ")");
                    }
                }
                for (int i = 1; i < sorted.size(); i++) {
                    if (sorted.get(i).minScore() < sorted.get(i - 1).maxScore()) {
                        throw new IllegalArgumentException("SCORECARD bands 区间重叠: "
                                + "[" + sorted.get(i - 1).minScore() + "," + sorted.get(i - 1).maxScore() + ") 与 "
                                + "[" + sorted.get(i).minScore() + "," + sorted.get(i).maxScore() + ")");
                    }
                }
            }
        }
        if (RuleKind.DECISION_TREE.tag().equals(kindTag)) {
            if (!(ast instanceof IfNode ifRoot)) {
                throw new IllegalArgumentException("kind=DECISION_TREE 的规则 conditionAst 根节点必须是 IfNode");
            }
            validateDecisionTree(ifRoot);
        }
        if (RuleKind.DECISION_TABLE.tag().equals(kindTag)) {
            if (!(ast instanceof DecisionTableNode tableRoot)) {
                throw new IllegalArgumentException("kind=DECISION_TABLE 的规则 conditionAst 根节点必须是 DecisionTableNode");
            }
            if (tableRoot.columns() == null || tableRoot.columns().isEmpty()) {
                throw new IllegalArgumentException("DECISION_TABLE columns 不得为空");
            }
            if (tableRoot.rows() == null || tableRoot.rows().isEmpty()) {
                throw new IllegalArgumentException("DECISION_TABLE rows 不得为空");
            }
            int colCount = tableRoot.columns().size();
            for (int i = 0; i < tableRoot.rows().size(); i++) {
                DecisionTableNode.Row row = tableRoot.rows().get(i);
                if (row.conditions().size() != colCount) {
                    throw new IllegalArgumentException("DECISION_TABLE 第 " + i + " 行 conditions 数量（"
                            + row.conditions().size() + "）与列数（" + colCount + "）不一致");
                }
            }
        }
    }

    /**
     * 创建规则草稿（主入口）。建规则定义 + 首版 DRAFT version，建草稿即冻结快照（premise A）。
     */
    @Transactional
    public DraftCreatedResult createDraft(Long tenantId, String sceneCode,
                                          String code, RuleContent content, String actorId) {
        return createDraft(tenantId, sceneCode, code, content, actorId, true);
    }

    /**
     * 创建规则草稿（严格模式可选）。模板实例化时 strictRefs=false 跳过 Flow RuleRef 冻结，
     * 允许被引规则尚不存在；发布时再校验。其余 kind 不受影响。
     */
    @Transactional
    public DraftCreatedResult createDraft(Long tenantId, String sceneCode,
                                          String code, RuleContent content, String actorId,
                                          boolean strictRefs) {
        String name = content.name();
        String kind = content.kind();
        RuleBody body = content.body();
        AstNode conditionAst = body instanceof AstBody ab ? ab.conditionAst() : null;
        java.util.List<RuleVersionSnapshot.DecisionBinding> decisionBindings = content.decisionBindings();
        java.util.List<RuleVersionSnapshot.PreGateConfig> preGates = content.preGates();
        java.util.List<String> triggerEventTypes = content.triggerEventTypes();
        ScriptSource script = body instanceof ScriptBody sb ? sb.script() : null;
        FlowGraph flowGraph = body instanceof FlowBody fb ? fb.flowGraph() : null;

        // 1. 按 tenantId + sceneCode 查询 SceneDef，不存在则报错
        SceneDef scene = sceneMapper.findByCode(tenantId, sceneCode);
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: code=" + sceneCode);
        }

        // 2. 校验 code 在同 tenant 下唯一（ruleCode 为 tenant 级业务标识），提前给出友好错误
        boolean codeExists = ruleDefinitionMapper.findByTenantAndCode(tenantId, code) != null;
        if (codeExists) {
            throw new IllegalArgumentException("规则编码已存在: code=" + code);
        }

        // 3. kind 合法性校验，null 时缺省 AST_BOOLEAN
        String effectiveKind = (kind == null || kind.isBlank()) ? RuleKind.AST_BOOLEAN.tag() : kind;
        java.util.Set<String> validKinds = java.util.Set.of(
                RuleKind.AST_BOOLEAN.tag(), RuleKind.SCORECARD.tag(),
                RuleKind.DECISION_TREE.tag(), RuleKind.DECISION_TABLE.tag(),
                RuleKind.EXPRESSION_SCRIPT.tag(), RuleKind.DECISION_FLOW.tag());
        if (!validKinds.contains(effectiveKind)) {
            throw new IllegalArgumentException("不支持的规则 kind: " + effectiveKind);
        }
        RuleKind effectiveRuleKind = RuleKind.valueOf(effectiveKind);
        validateKindBodyConsistent(effectiveRuleKind, body);

        // 4. INSERT rule_definition（status=DRAFT）
        RuleDefinition rd = RuleDefinition.draft(tenantId, sceneCode, code, name, effectiveRuleKind, actorId);
        ruleDefinitionMapper.insert(rd);

        // 5. resolveAndValidate（premise A）：建草稿即冻结快照；strictRefs=false 时跳过 Flow RuleRef 冻结
        ResolvedDraft resolved = resolveAndValidate(
                tenantId, scene, effectiveRuleKind,
                conditionAst, decisionBindings, preGates, triggerEventTypes, script, flowGraph, strictRefs);
        RuleVersion rv = buildDraftVersion(rd.getId(), 1L, resolved);
        ruleVersionMapper.insert(rv);

        // 6. 发布操作审计事件（集中监听器 BEFORE_COMMIT 同事务落 audit_log，D14 约定）
        // CREATE 类 before/after 传同一快照实例，审计行始终 before/after 都有值，避免 null 特殊处理
        DraftCreatedSnapshot draftSnapshot = new DraftCreatedSnapshot(rd.getId(), rv.getId());
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.CREATE, AuditTargetType.RULE_DEFINITION, rd.getId().toString(),
                draftSnapshot,
                draftSnapshot,
                LocalDateTime.now()));

        return new DraftCreatedResult(rd.getId(), rv.getId(), 1L, RuleDefinitionStatus.DRAFT.name());
    }

    /** 解析 kind 字符串为 RuleKind，null/空返回 null（由下游兜底现有 kind 或 AST_BOOLEAN），非法抛 IllegalArgumentException。 */
    private static RuleKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return RuleKind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的规则 kind: " + kind);
        }
    }

    /** 把发布期解析产物 ResolvedDraft 打包为持久化多态载体 RuleBody（flow&gt;script&gt;ast）。 */
    private static RuleBody toBody(ResolvedDraft r) {
        if (r.flowGraph() != null) return new FlowBody(r.flowGraph(), r.referencedSnapshots());
        if (r.scriptSource() != null) return new ScriptBody(r.scriptSource());
        return new AstBody(r.resolvedAst());
    }

    /** 校验 kind 家族与 body 变体一致（不一致抛 KIND_BODY_MISMATCH）；body 为 null 时跳过（下游按 kind 兜底）。 */
    private static void validateKindBodyConsistent(RuleKind kind, RuleBody body) {
        if (body == null) return;
        boolean ok = switch (kind) {
            case AST_BOOLEAN, SCORECARD, DECISION_TREE, DECISION_TABLE -> body instanceof AstBody;
            case EXPRESSION_SCRIPT -> body instanceof ScriptBody;
            case DECISION_FLOW -> body instanceof FlowBody;
        };
        if (!ok) {
            throw new IllegalArgumentException("KIND_BODY_MISMATCH: kind=" + kind
                    + " 与 body 类型 " + body.getClass().getSimpleName() + " 不一致");
        }
    }

    /** 用冻结内容组装 DRAFT 版本行（createDraft/newVersion 共用）。 */
    private RuleVersion buildDraftVersion(Long ruleDefinitionId, long version, ResolvedDraft r) {
        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(ruleDefinitionId);
        rv.setVersion(version);
        rv.setBody(toBody(r));
        rv.setDecisionBindings(r.decisionBindings());
        rv.setPreGates(r.preGates());
        rv.setKind(r.kind());
        rv.setTriggerEventTypes(r.triggerEventTypes());
        rv.setMetricDependencies(r.metricDeps());
        rv.setPayloadDependencies(r.payloadDeps());
        rv.setStatus(RuleVersionStatus.DRAFT);
        rv.setCreatedAt(LocalDateTime.now());
        return rv;
    }

    /**
     * 从 decision_definition 批量回填 band 的 name/priority，返回重建的 ScoreBand 列表（不可变）。
     * band decisionCode 不存在时抛含 "DECISION_CODE_NOT_FOUND" 的 IllegalArgumentException。
     */
    private List<ScoreBand> enrichBands(Long tenantId, List<ScoreBand> bands) {
        if (bands.isEmpty()) return List.of();
        List<String> codes = bands.stream().map(ScoreBand::decisionCode).distinct().toList();
        Map<String, DecisionDefinition> byCode = decisionDefinitionMapper.findByCodes(tenantId, codes)
                .stream().collect(Collectors.toMap(DecisionDefinition::getCode, d -> d, (a, b) -> a));
        List<ScoreBand> result = new ArrayList<>(bands.size());
        for (ScoreBand band : bands) {
            DecisionDefinition d = byCode.get(band.decisionCode());
            if (d == null) {
                throw new IllegalArgumentException("DECISION_CODE_NOT_FOUND: bands 引用的 decision 不存在: " + band.decisionCode());
            }
            int priority = d.getPriority() != null ? d.getPriority() : 0;
            result.add(new ScoreBand(band.minScore(), band.maxScore(), band.decisionCode(),
                    band.category(), d.getName() != null ? d.getName() : "", priority));
        }
        return java.util.Collections.unmodifiableList(result);
    }

    /**
     * 把 draft 的 (decisionCode, priority) binding 富化为含 name 的快照 binding（方案甲，守 D6）。
     * 引用的 decisionCode 必须在 decision_definition 存在，否则拒绝发布（DECISION_CODE_NOT_FOUND）。
     */
    private List<RuleVersionSnapshot.DecisionBinding> freezeDecisionBindings(
            Long tenantId, SceneDef scene, List<RuleVersionSnapshot.DecisionBinding> rawBindings) {
        if (rawBindings.isEmpty()) return java.util.List.of();
        List<String> codes = rawBindings.stream()
                .map(RuleVersionSnapshot.DecisionBinding::decisionCode).distinct().toList();
        Map<String, DecisionDefinition> byCode = decisionDefinitionMapper.findByCodes(tenantId, codes).stream()
                .collect(Collectors.toMap(DecisionDefinition::getCode, d -> d, (a, b) -> a));
        List<RuleVersionSnapshot.DecisionBinding> frozen = new ArrayList<>(rawBindings.size());
        for (RuleVersionSnapshot.DecisionBinding b : rawBindings) {
            DecisionDefinition d = byCode.get(b.decisionCode());
            if (d == null) {
                throw new IllegalArgumentException(
                        "DECISION_CODE_NOT_FOUND: 引用的 decision 不存在: " + b.decisionCode());
            }
            // priority 从 decision_definition 回填(草稿期 binding priority 是 0 占位，DecisionBindingInput 契约)
            int priority = d.getPriority() != null ? d.getPriority() : b.priority();
            frozen.add(new RuleVersionSnapshot.DecisionBinding(
                    b.decisionCode(), d.getName(), priority));
        }
        return frozen;
    }

    /**
     * 校验 pre_gates 中 ROLLOUT 项的 params 合法性（仅单规则校验，不查兄弟规则）。
     * percentage∈[0,100]；若给桶区间则 0<=bucketStart<bucketEnd<=100；experimentId 非空白。
     * pre_gates JSON 格式异常时容错跳过（不阻断发布），仅参数语义越界抛 IllegalArgumentException。
     */
    private void validatePreGateParams(List<RuleVersionSnapshot.PreGateConfig> gates) {
        if (gates == null || gates.isEmpty()) return;
        for (RuleVersionSnapshot.PreGateConfig gate : gates) {
            String gateType = gate.gateType();
            // pre-gate 收敛:仅 ROLLOUT / TIME_WINDOW 是注册的合法 gate;RATE_LIMIT/MUTEX 等已砍,配了即拒绝发布
            if (!"ROLLOUT".equals(gateType) && !"TIME_WINDOW".equals(gateType)) {
                throw new IllegalArgumentException(
                        "不支持的 pre-gate gateType(仅 ROLLOUT/TIME_WINDOW 合法): " + gateType);
            }
            if (gate.params() == null) continue;
            if ("TIME_WINDOW".equals(gateType)) {
                validateTimeWindowParams(gate.params());
                continue;
            }
            RolloutParams params = RolloutParams.from(gate.params());

            if (params.percentage() != null
                    && (params.percentage() < 0 || params.percentage() > 100)) {
                throw new IllegalArgumentException(
                        "ROLLOUT percentage 必须在 [0,100]，实际值: " + params.percentage());
            }
            boolean hasStart = params.bucketStart() != null;
            boolean hasEnd = params.bucketEnd() != null;
            if (hasStart != hasEnd) {
                throw new IllegalArgumentException(
                        "ROLLOUT bucketStart/bucketEnd 必须成对出现");
            }
            if (hasStart) {
                int s = params.bucketStart(), en = params.bucketEnd();
                if (s < 0 || en > 100 || s >= en) {
                    throw new IllegalArgumentException(
                            "ROLLOUT 桶区间非法，要求 0<=bucketStart<bucketEnd<=100，实际: ["
                                    + s + "," + en + ")");
                }
            }
            if (params.experimentId() != null && params.experimentId().isBlank()) {
                throw new IllegalArgumentException(
                        "ROLLOUT experimentId 不得为空白字符串");
            }
        }
    }

    /**
     * 校验 TIME_WINDOW pre-gate 参数：from/to 均给定时须 from<=to（否则窗口永不命中）；
     * 单边或皆空合法（皆空即无时段约束 fail-open）。
     */
    private void validateTimeWindowParams(Map<String, Object> params) {
        TimeWindowParams p = TimeWindowParams.from(params);
        if (p.fromEpochMilli() != null && p.toEpochMilli() != null
                && p.fromEpochMilli() > p.toEpochMilli()) {
            throw new IllegalArgumentException(
                    "TIME_WINDOW fromEpochMilli 必须 <= toEpochMilli，实际: ["
                            + p.fromEpochMilli() + "," + p.toEpochMilli() + "]");
        }
    }

    /**
     * 校验规则的 triggerEventTypes 是否均在 Scene 允许的 eventTypes 白名单内。
     * scene.eventTypes 为空时跳过（Scene 尚未配置白名单，容错）；
     * triggerEventTypes 为空时也跳过（规则通配所有事件）。
     */
    private void validateTriggerEventTypes(List<String> ruleTypes, List<String> sceneTypes) {
        if (ruleTypes == null || ruleTypes.isEmpty()) return;
        if (sceneTypes == null || sceneTypes.isEmpty()) return;   // Scene 未设置白名单，容错通过

        java.util.Set<String> allowed = new java.util.HashSet<>(sceneTypes);
        java.util.List<String> invalid = ruleTypes.stream()
                .filter(et -> !allowed.contains(et))
                .toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(
                    "triggerEventType 不在 Scene 允许列表，非法值: " + invalid);
        }
    }

    /**
     * 递归校验决策树结构：分支节点只能是 IfNode 或 DecisionLeafNode，每个 IfNode 的 thenBranch 非空、
     * 条件子树仅含决策树支持的节点（见 {@link #validateTreeCondition}）。
     */
    private static void validateDecisionTree(AstNode node) {
        switch (node) {
            case IfNode ifn -> {
                if (ifn.thenBranch() == null) {
                    throw new IllegalArgumentException("kind=DECISION_TREE 的 IfNode thenBranch 不得为 null");
                }
                if (ifn.condition() == null) {
                    throw new IllegalArgumentException("kind=DECISION_TREE 的 IfNode condition 不得为 null");
                }
                validateTreeCondition(ifn.condition());
                validateDecisionTree(ifn.thenBranch());
                if (ifn.elseBranch() != null) validateDecisionTree(ifn.elseBranch());
            }
            case DecisionLeafNode ignored -> { /* 终点叶子，合法 */ }
            default -> throw new IllegalArgumentException(
                    "kind=DECISION_TREE 的分支节点只能是 IfNode 或 DecisionLeafNode，实际: "
                            + node.getClass().getSimpleName());
        }
    }

    /**
     * 决策树条件子树仅支持 ConditionNode/AndNode/OrNode/NotNode；出现 XorNode 等不支持的节点即拒绝发布
     * （决策树条件求值不支持 XOR，避免上线后运行时才报 NO_EVALUATOR）。
     */
    private static void validateTreeCondition(AstNode cond) {
        switch (cond) {
            case ConditionNode ignored -> {
            }
            case AndNode and -> and.children().forEach(PublishService::validateTreeCondition);
            case OrNode or -> or.children().forEach(PublishService::validateTreeCondition);
            case NotNode not -> validateTreeCondition(not.child());
            default -> throw new IllegalArgumentException(
                    "kind=DECISION_TREE 的条件不支持节点类型: " + cond.getClass().getSimpleName()
                            + "（决策树条件仅支持 Condition/And/Or/Not；XOR 等逻辑请用 AST_BOOLEAN kind）");
        }
    }
}
