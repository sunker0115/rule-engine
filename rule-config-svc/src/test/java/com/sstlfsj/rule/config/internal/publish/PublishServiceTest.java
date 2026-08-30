package com.sstlfsj.rule.config.internal.publish;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.internal.event.DraftCreatedSnapshot;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// MyBatis-Plus 3.5.16 的 insert/updateById 有重载（单对象 vs Collection），
// argThat 需要显式类型参数消除歧义；publishEvent 用 ArgumentCaptor 避免 ApplicationEvent 重载问题。
// 二期后 conditionAst 已 typed：草稿 AST 直接 set 到 draftVersion，不再 mock AstSerializer。

@ExtendWith(MockitoExtension.class)
class PublishServiceTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Spy ObjectMapper objectMapper = JsonMapper.builder().build();
    @Mock MetricDefinitionMapper metricDefinitionMapper;

    @InjectMocks PublishService publishService;

    private RuleDefinition draftRule;
    private SceneDef scene;
    private RuleVersion draftVersion;

    @BeforeEach
    void setUp() {
        draftRule = new RuleDefinition();
        draftRule.setId(10L);
        draftRule.setTenantId(1L);
        draftRule.setSceneCode("PAYMENT");
        draftRule.setCode("rule.demo");
        draftRule.setName("测试规则");
        draftRule.setStatus(RuleDefinitionStatus.DRAFT);
        draftRule.setKind(RuleKind.AST_BOOLEAN);

        scene = new SceneDef();
        scene.setId(5L);
        scene.setCode("PAYMENT");
        scene.setEventTypes(java.util.List.of("payment.initiated"));
        scene.setStatus(SceneStatus.ACTIVE);

        draftVersion = new RuleVersion();
        draftVersion.setId(100L);
        draftVersion.setRuleDefinitionId(10L);
        draftVersion.setVersion(0L);
        draftVersion.setBody(new AstBody(new ConditionNode("c.type", "m.code", null, Map.of(), 0.0)));
        draftVersion.setDecisionBindings(List.of());
        draftVersion.setPreGates(List.of());
        draftVersion.setStatus(RuleVersionStatus.DRAFT);
    }

    @Test
    void publish_activatesDraftInPlace() {
        draftRule.setCurrentVersion(null);
        draftVersion.setVersion(1L);
        draftVersion.setBody(new AstBody(new ConditionNode("GT", "amount", "LONG", Map.of("threshold", 1), 0.0)));
        draftVersion.setMetricDependencies(List.of(new MetricDependency("amount", 1)));
        draftVersion.setPayloadDependencies(List.of());
        draftVersion.setTriggerEventTypes(List.of());
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.updateById((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        RuleVersionSnapshot snapshot = publishService.publish(1L, 10L, "operator1");

        assertThat(snapshot.sceneCode()).isEqualTo("PAYMENT");
        // 快照携带规则逻辑身份 code(来自 rule_definition) + version(来自被激活的 rule_version)
        assertThat(snapshot.code()).isEqualTo("rule.demo");
        assertThat(snapshot.version()).isEqualTo(1L);
        assertThat(snapshot.metricDependencies()).containsExactly(new MetricDependency("amount", 1));
        // DRAFT 行原地翻 ACTIVE,无新 insert
        ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).updateById(rvCaptor.capture());
        assertThat(rvCaptor.getValue().getStatus()).isEqualTo(RuleVersionStatus.ACTIVE);
        assertThat(rvCaptor.getValue().getVersion()).isEqualTo(1L);
        verify(ruleVersionMapper, never()).insert((RuleVersion) any());
        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo(RuleDefinitionStatus.PUBLISHED);
        assertThat(rdCaptor.getValue().getCurrentVersion()).isEqualTo(100L);
        // 发布发两个事件：审计事件（PUBLISH）+ Modulith RulePublishedEvent
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
    }

    @Test
    void publish_supersedesPreviousActive() {
        draftRule.setStatus(RuleDefinitionStatus.PUBLISHED);
        draftRule.setCurrentVersion(99L);
        draftVersion.setVersion(2L);
        draftVersion.setBody(new AstBody(new AndNode(List.of(), null, null)));
        draftVersion.setMetricDependencies(List.of());
        draftVersion.setPayloadDependencies(List.of());
        draftVersion.setTriggerEventTypes(List.of());
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.updateById((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        publishService.publish(1L, 10L, "op");

        verify(ruleVersionMapper).markSuperseded(99L);
    }

    @Test
    void publish_ruleNotFound_throwsIllegalArgument() {
        when(ruleDefinitionMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> publishService.publish(1L, 99L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规则不存在");
    }

    @Test
    void publish_noDraftVersion_throwsIllegalState() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(null);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有待发布的草稿版本");
    }

    @Test
    void editDraft_updatesLatestDraftInPlace_noVersionBump() {
        draftRule.setKind(RuleKind.AST_BOOLEAN);
        draftVersion.setVersion(1L);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(draftVersion);
        when(ruleVersionMapper.updateById((RuleVersion) any())).thenReturn(1);
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("amount"); md.setDataType("LONG"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        DraftCreatedResult r = publishService.editDraft(1L, 10L,
                new RuleContent("新名", RuleKind.AST_BOOLEAN.name(),
                        new AstBody(new ConditionNode("GT", "amount", null, Map.of("threshold", 5), 0.0)),
                        List.of(), List.of(), List.of()),
                "actor");

        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.status()).isEqualTo("DRAFT");
        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).updateById(cap.capture());
        assertThat(cap.getValue().getVersion()).isEqualTo(1L);
        assertThat(((ConditionNode) ((AstBody) cap.getValue().getBody()).conditionAst()).dataType()).isEqualTo("LONG");
        verify(ruleVersionMapper, never()).insert((RuleVersion) any());
    }

    @Test
    void editDraft_kindOmitted_preservesExistingDraftKind() {
        // kind 省略(null)时应保留草稿现有 kind(SCORECARD)，而非静默重置为 AST_BOOLEAN
        draftRule.setKind(RuleKind.SCORECARD);
        draftVersion.setVersion(1L);
        draftVersion.setKind(RuleKind.SCORECARD);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(draftVersion);
        when(ruleVersionMapper.updateById((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("score"); md.setDataType("LONG"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        // 合法 ScorecardRootNode（叶子 weight>0），kind 入参传 null
        ConditionNode leaf = new ConditionNode("GT", "score", null, Map.of("threshold", 1), 5.0);
        publishService.editDraft(1L, 10L,
                new RuleContent(null, null,
                        new AstBody(new ScorecardRootNode(List.of(leaf), 60.0, java.util.List.of())),
                        List.of(), List.of(), List.of()),
                "actor");

        ArgumentCaptor<RuleVersion> rvCap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).updateById(rvCap.capture());
        assertThat(rvCap.getValue().getKind()).isEqualTo(RuleKind.SCORECARD);
        ArgumentCaptor<RuleDefinition> rdCap = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(rdCap.capture());
        assertThat(rdCap.getValue().getKind()).isEqualTo(RuleKind.SCORECARD);
    }

    @Test
    void editDraft_conditionMissingRequiredParamKey_rejected() {
        // 草稿保存期接入 ConditionParamValidator：GT 算子缺必填 threshold 键应被拦下
        draftRule.setKind(RuleKind.AST_BOOLEAN);
        draftVersion.setVersion(1L);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.editDraft(1L, 10L,
                new RuleContent("名", RuleKind.AST_BOOLEAN.name(),
                        new AstBody(new ConditionNode("GT", "amount", null, Map.of(), 0.0)),
                        List.of(), List.of(), List.of()),
                "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填参数键");
        verify(ruleVersionMapper, never()).updateById((RuleVersion) any());
    }

    @Test
    void editDraft_noDraft_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(null);
        assertThatThrownBy(() -> publishService.editDraft(1L, 10L,
                new RuleContent("n", RuleKind.AST_BOOLEAN.name(),
                        new AstBody(new AndNode(List.of(), null, null)), List.of(), List.of(), List.of()),
                "actor"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("草稿");
    }

    @Test
    void createDraft_insertsRuleDefinitionAndVersion() {
        SceneDef draftScene = new SceneDef();
        draftScene.setId(5L);
        draftScene.setTenantId(1L);
        draftScene.setCode("risk.transfer");
        when(sceneMapper.findByCode(any(), any())).thenReturn(draftScene);
        when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn((RuleDefinition) null);

        doAnswer(inv -> {
            RuleDefinition rd = inv.getArgument(0);
            rd.setId(10L);
            return 1;
        }).when(ruleDefinitionMapper).insert(any(RuleDefinition.class));

        doAnswer(inv -> {
            RuleVersion rv = inv.getArgument(0);
            rv.setId(20L);
            return 1;
        }).when(ruleVersionMapper).insert(any(RuleVersion.class));


        // SCORECARD + 空 AndNode 现会被结构校验拒，改用 AST_BOOLEAN + 空 AndNode（无 metric/payload 引用）
        DraftCreatedResult result = publishService.createDraft(
                1L, "risk.transfer", "rule.test",
                new RuleContent("测试规则", "AST_BOOLEAN",
                        new AstBody(new com.sstlfsj.rule.kernel.api.model.ast.AndNode(java.util.List.of(), null, null)),
                        java.util.List.of(), java.util.List.of(), java.util.List.of()),
                "actor1");

        assertThat(result.ruleDefinitionId()).isEqualTo(10L);
        assertThat(result.ruleVersionId()).isEqualTo(20L);
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo("DRAFT");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).insert(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo(RuleDefinitionStatus.DRAFT);
        assertThat(rdCaptor.getValue().getCode()).isEqualTo("rule.test");
        assertThat(rdCaptor.getValue().getKind()).isEqualTo(RuleKind.AST_BOOLEAN);

        ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(rvCaptor.capture());
        assertThat(rvCaptor.getValue().getVersion()).isEqualTo(1L);
        assertThat(rvCaptor.getValue().getStatus()).isEqualTo(RuleVersionStatus.DRAFT);
        assertThat(rvCaptor.getValue().getKind()).isEqualTo(RuleKind.AST_BOOLEAN);
        // conditionAst 直传 typed 落库（无 JSON 串来回）
        assertThat(((AstBody) rvCaptor.getValue().getBody()).conditionAst())
                .isInstanceOf(com.sstlfsj.rule.kernel.api.model.ast.AndNode.class);
        // 无 metric/payload 引用，冻结依赖均为空
        assertThat(rvCaptor.getValue().getMetricDependencies()).isEmpty();
        assertThat(rvCaptor.getValue().getPayloadDependencies()).isEmpty();

        // CREATE 类审计：before/after 为同一个 typed DraftCreatedSnapshot 实例
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        OperationAuditedEvent audit = eventCaptor.getAllValues().stream()
                .filter(OperationAuditedEvent.class::isInstance)
                .map(OperationAuditedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(audit.action()).isEqualTo(com.sstlfsj.rule.config.internal.domain.AuditAction.CREATE);
        assertThat(audit.beforeSnapshot()).isSameAs(audit.afterSnapshot());
        assertThat(audit.afterSnapshot()).isEqualTo(new DraftCreatedSnapshot(10L, 20L));
    }

    @Test
    void createDraft_kindBodyMismatch_rejected() {
        // D76：kind=AST_BOOLEAN 但 body 为 ScriptBody → 发布期 kind↔body 一致校验拒绝，规则未落库
        SceneDef sc = new SceneDef();
        sc.setId(5L); sc.setTenantId(1L); sc.setCode("PAYMENT");
        when(sceneMapper.findByCode(any(), any())).thenReturn(sc);
        lenient().when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn(null);

        RuleContent content = new RuleContent("mismatch", "AST_BOOLEAN",
                new ScriptBody(new ScriptSource("x > 1", "CEL")), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> publishService.createDraft(1L, "PAYMENT", "rule.mm", content, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KIND_BODY_MISMATCH");
        verify(ruleDefinitionMapper, never()).insert(any(RuleDefinition.class));
    }

    @Test
    void createDraft_sceneNotFound_throwsIllegalArgument() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () ->
                publishService.createDraft(1L, "nonexistent", "rule.test",
                        new RuleContent("测试", null, null, null, null, null), "actor1"));
    }

    @Test
    void createDraft_duplicateCode_throwsIllegalArgument() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("risk.transfer");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);
        // 模拟同 tenant+scene 下已存在同 code 的规则
        when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn(new RuleDefinition());

        assertThrows(IllegalArgumentException.class, () ->
                publishService.createDraft(1L, "risk.transfer", "rule.test",
                        new RuleContent("测试", null, null, null, null, null), "actor1"));

        verify(ruleDefinitionMapper, never()).insert(any(RuleDefinition.class));
    }

    @Test
    void createDraft_invalidKind_throwsIllegalArgument() {
        SceneDef draftScene = new SceneDef();
        draftScene.setId(5L);
        draftScene.setTenantId(1L);
        draftScene.setCode("risk.transfer");
        when(sceneMapper.findByCode(any(), any())).thenReturn(draftScene);

        assertThatThrownBy(() -> publishService.createDraft(
                1L, "risk.transfer", "rule.test",
                new RuleContent("测试规则", "NO_SUCH_KIND", null, null, null, null), "actor1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的规则 kind");
    }

    @Test
    void createDraft_nullKind_defaultsToAstBoolean() {
        SceneDef draftScene = new SceneDef();
        draftScene.setId(5L);
        draftScene.setTenantId(1L);
        draftScene.setCode("risk.transfer");
        when(sceneMapper.findByCode(any(), any())).thenReturn(draftScene);
        when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn((RuleDefinition) null);

        doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(20L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));

        publishService.createDraft(1L, "risk.transfer", "rule.test",
                new RuleContent("测试规则", null,
                        new AstBody(new com.sstlfsj.rule.kernel.api.model.ast.AndNode(java.util.List.of(), null, null)),
                        java.util.List.of(), java.util.List.of(), java.util.List.of()),
                "actor1");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).insert(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getKind()).isEqualTo(RuleKind.AST_BOOLEAN);
    }

    @Test
    void createDraft_freezesMetricAndDecision_intoDraftVersion() {
        SceneDef sc = new SceneDef();
        sc.setId(5L); sc.setTenantId(1L); sc.setCode("PAYMENT");
        sc.setEventTypes(List.of("payment.initiated"));
        when(sceneMapper.findByCode(any(), any())).thenReturn(sc);
        when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn(null);
        doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(20L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("account.age"); md.setDataType("LONG"); md.setVersion(3); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        publishService.createDraft(1L, "PAYMENT", "rule.test",
                new RuleContent("测试", "AST_BOOLEAN",
                        new AstBody(new ConditionNode("GT", "account.age", null, Map.of("threshold", 30), 0.0)),
                        List.of(), List.of(), List.of()),
                "actor1");

        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(cap.capture());
        RuleVersion frozen = cap.getValue();
        assertThat(frozen.getStatus()).isEqualTo(RuleVersionStatus.DRAFT);
        assertThat(frozen.getMetricDependencies()).containsExactly(new MetricDependency("account.age", 3));
        assertThat(((ConditionNode) ((AstBody) frozen.getBody()).conditionAst()).dataType()).isEqualTo("LONG");
    }

    @Test
    void createDraft_metricNotActive_rejected() {
        SceneDef sc = new SceneDef();
        sc.setId(5L); sc.setTenantId(1L); sc.setCode("PAYMENT");
        when(sceneMapper.findByCode(any(), any())).thenReturn(sc);
        when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn(null);
        doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> publishService.createDraft(1L, "PAYMENT", "rule.test",
                new RuleContent("测试", "AST_BOOLEAN",
                        new AstBody(new ConditionNode("GT", "account.age", null, Map.of("threshold", 30), 0.0)),
                        List.of(), List.of(), List.of()),
                "actor1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void newVersion_requiresNoPendingDraft_createsNextVersion() {
        draftRule.setStatus(RuleDefinitionStatus.PUBLISHED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(null);   // 无待发布草稿
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(1L);
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(30L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("amount"); md.setDataType("LONG"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        DraftCreatedResult r = publishService.newVersion(1L, 10L,
                new RuleContent(null, RuleKind.AST_BOOLEAN.name(),
                        new AstBody(new ConditionNode("GT", "amount", null, Map.of("threshold", 9), 0.0)),
                        List.of(), List.of(), List.of()),
                null, "actor");

        assertThat(r.version()).isEqualTo(2L);
        assertThat(r.status()).isEqualTo("DRAFT");
        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RuleVersionStatus.DRAFT);
        assertThat(cap.getValue().getVersion()).isEqualTo(2L);
    }

    @Test
    void newVersion_pendingDraftExists_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(draftVersion);   // 已有 DRAFT
        assertThatThrownBy(() -> publishService.newVersion(1L, 10L,
                new RuleContent(null, RuleKind.AST_BOOLEAN.name(),
                        new AstBody(new AndNode(List.of(), null, null)), List.of(), List.of(), List.of()),
                null, "actor"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("待发布");
    }

    @Test
    void rollback_clonesFromOldVersion_reresolvesAgainstCurrentWorld() {
        draftRule.setStatus(RuleDefinitionStatus.PUBLISHED);
        RuleVersion oldV = new RuleVersion();
        oldV.setId(50L); oldV.setRuleDefinitionId(10L); oldV.setVersion(1L);
        oldV.setBody(new AstBody(new ConditionNode("GT", "amount", "LONG", Map.of("threshold", 1), 0.0)));
        oldV.setDecisionBindings(List.of()); oldV.setPreGates(List.of()); oldV.setTriggerEventTypes(List.of());
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.findByCode(1L, "PAYMENT")).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(null);
        when(ruleVersionMapper.findByIdAndRule(50L, 10L)).thenReturn(oldV);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(2L);
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(40L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("amount"); md.setDataType("LONG"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        DraftCreatedResult r = publishService.newVersion(1L, 10L,
                new RuleContent(null, RuleKind.AST_BOOLEAN.name(), null, null, null, null),
                50L, "actor");

        assertThat(r.version()).isEqualTo(3L);   // v_max+1,克隆 v1 内容
        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(cap.capture());
        assertThat(((ConditionNode) ((AstBody) cap.getValue().getBody()).conditionAst()).metricCode()).isEqualTo("amount");
    }

    @Test
    void deleteRule_neverPublished_cascadeDeletes() {
        draftRule.setStatus(RuleDefinitionStatus.DRAFT);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(ruleVersionMapper.hasNonDraftVersion(10L)).thenReturn(false);
        when(ruleVersionMapper.deleteByRuleDefinitionId(10L)).thenReturn(1);
        when(ruleDefinitionMapper.deleteById(10L)).thenReturn(1);

        publishService.deleteRule(1L, 10L, "actor");

        verify(ruleVersionMapper).deleteByRuleDefinitionId(10L);
        verify(ruleDefinitionMapper).deleteById(10L);
        // D14:删除是破坏性操作，必须发 DELETE 审计事件，before 捕获删前规则状态
        ArgumentCaptor<Object> evCap = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        OperationAuditedEvent audit = evCap.getAllValues().stream()
                .filter(OperationAuditedEvent.class::isInstance)
                .map(OperationAuditedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(audit.action()).isEqualTo(com.sstlfsj.rule.config.internal.domain.AuditAction.DELETE);
        assertThat(audit.targetType()).isEqualTo(com.sstlfsj.rule.config.internal.domain.AuditTargetType.RULE_DEFINITION);
        assertThat(audit.beforeSnapshot()).isInstanceOf(
                com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot.class);
    }

    @Test
    void deleteRule_published_rejected() {
        draftRule.setStatus(RuleDefinitionStatus.PUBLISHED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(ruleVersionMapper.hasNonDraftVersion(10L)).thenReturn(true);
        assertThatThrownBy(() -> publishService.deleteRule(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已发布");
        verify(ruleDefinitionMapper, never()).deleteById((Long) any());
    }

    @Test
    void deleteDraftVersion_draftStatus_deletesRow() {
        draftVersion.setStatus(RuleVersionStatus.DRAFT);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(ruleVersionMapper.findByIdAndRule(100L, 10L)).thenReturn(draftVersion);
        when(ruleVersionMapper.deleteById(100L)).thenReturn(1);
        publishService.deleteDraftVersion(1L, 10L, 100L, "actor");
        verify(ruleVersionMapper).deleteById(100L);
        // D14:删单个草稿版本也发 DELETE 审计，targetType=rule_version
        ArgumentCaptor<Object> evCap = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        OperationAuditedEvent audit = evCap.getAllValues().stream()
                .filter(OperationAuditedEvent.class::isInstance)
                .map(OperationAuditedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(audit.action()).isEqualTo(com.sstlfsj.rule.config.internal.domain.AuditAction.DELETE);
        assertThat(audit.targetType()).isEqualTo(com.sstlfsj.rule.config.internal.domain.AuditTargetType.RULE_VERSION);
        assertThat(audit.beforeSnapshot()).isInstanceOf(
                com.sstlfsj.rule.config.internal.event.DraftCreatedSnapshot.class);
    }

    @Test
    void deleteDraftVersion_nonDraft_rejected() {
        draftVersion.setStatus(RuleVersionStatus.ACTIVE);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(ruleVersionMapper.findByIdAndRule(100L, 10L)).thenReturn(draftVersion);
        assertThatThrownBy(() -> publishService.deleteDraftVersion(1L, 10L, 100L, "actor"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DRAFT");
        verify(ruleVersionMapper, never()).deleteById((Long) any());
    }

    // ===== SCORECARD bands 发布期校验 + band decisionCode 回填 =====

    @Test
    void scorecardBands_overlap_rejected() {
        // [0,60) 与 [50,80) 重叠 → 拒绝
        ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
                List.of(new com.sstlfsj.rule.kernel.api.model.ast.ScoreBand(0, 60, "A", null),
                        new com.sstlfsj.rule.kernel.api.model.ast.ScoreBand(50, 80, "B", null)));
        decisionsExist("A", "B");
        assertThatThrownBy(() -> publishScorecard(ast))
                .hasMessageContaining("重叠");
    }

    @Test
    void scorecardBands_minGeMax_rejected() {
        ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
                List.of(new com.sstlfsj.rule.kernel.api.model.ast.ScoreBand(80, 60, "A", null)));
        decisionsExist("A");
        assertThatThrownBy(() -> publishScorecard(ast))
                .hasMessageContaining("minScore");
    }

    @Test
    void scorecardBands_decisionNotFound_rejectedByEnrichBands() {
        // bands 里 decisionCode 不存在 → enrichBands 应拒绝
        ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
                List.of(new com.sstlfsj.rule.kernel.api.model.ast.ScoreBand(0, 60, "MISSING", null)));
        decisionsExist(/* none */);
        assertThatThrownBy(() -> publishScorecard(ast))
                .hasMessageContaining("DECISION_CODE_NOT_FOUND");
    }

    @Test
    void scorecardBands_valid_enrichesBandsInAstNotDecisionBindings() {
        ScorecardRootNode ast = new ScorecardRootNode(List.of(weightedCond()), 0.0,
                List.of(new com.sstlfsj.rule.kernel.api.model.ast.ScoreBand(0, 60, "REJECT", "HIGH"),
                        new com.sstlfsj.rule.kernel.api.model.ast.ScoreBand(60, 100, "PASS", "LOW")));
        decisionsExist("REJECT", "PASS");

        publishScorecard(ast);

        // band 的 name/priority 直接回填进 ScoreBand（不再注入 decisionBindings）
        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(cap.capture());
        assertThat(cap.getValue().getDecisionBindings()).isEmpty();
        ScorecardRootNode resolved = (ScorecardRootNode) ((AstBody) cap.getValue().getBody()).conditionAst();
        com.sstlfsj.rule.kernel.api.model.ast.ScoreBand reject = resolved.bands().stream()
                .filter(b -> b.decisionCode().equals("REJECT")).findFirst().orElseThrow();
        assertThat(reject.name()).isEqualTo("REJECT-name");
        assertThat(reject.priority()).isEqualTo(7);
        assertThat(reject.category()).isEqualTo("HIGH");
    }

    /** 带 weight>0 的 SCORECARD 叶子条件（无 metric/payload 引用，走纯条件）。 */
    private static ConditionNode weightedCond() {
        return new ConditionNode("GT", "score", null, Map.of("threshold", 1), 5.0);
    }

    /** mock decisionDefinitionMapper.findByCodes：给定 codes 视为存在（name=<code>-name, priority=7）。 */
    private void decisionsExist(String... codes) {
        List<DecisionDefinition> defs = new java.util.ArrayList<>();
        for (String c : codes) {
            DecisionDefinition d = new DecisionDefinition();
            d.setCode(c);
            d.setName(c + "-name");
            d.setPriority(7);
            d.setStatus(DecisionStatus.ACTIVE);
            defs.add(d);
        }
        // lenient：bands 结构非法的用例在 validateKindStructure 即拒，触不到 findByCodes
        lenient().when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(defs);
    }

    /** 经 createDraft 发布一条 SCORECARD 规则，触发 resolveAndValidate（含 validateKindStructure + freezeDecisionBindings）。 */
    private DraftCreatedResult publishScorecard(ScorecardRootNode ast) {
        SceneDef sc = new SceneDef();
        sc.setId(5L); sc.setTenantId(1L); sc.setCode("PAYMENT");
        sc.setEventTypes(List.of("payment.initiated"));
        when(sceneMapper.findByCode(any(), any())).thenReturn(sc);
        when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn(null);
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("score"); md.setDataType("LONG"); md.setStatus(MetricStatus.ACTIVE);
        lenient().when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));
        lenient().doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        lenient().doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(20L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));
        return publishService.createDraft(1L, "PAYMENT", "rule.scorecard",
                new RuleContent("评分卡", RuleKind.SCORECARD.name(), new AstBody(ast),
                        List.of(), List.of(), List.of()),
                "actor");
    }

    // ===== TIME_WINDOW pre-gate 发布期校验 =====

    @Test
    void createDraft_timeWindowFromAfterTo_rejected() {
        // TIME_WINDOW from>to 窗口永不命中,发布期应拒
        SceneDef sc = new SceneDef();
        sc.setId(5L); sc.setTenantId(1L); sc.setCode("PAYMENT");
        sc.setEventTypes(List.of("payment.initiated"));
        when(sceneMapper.findByCode(any(), any())).thenReturn(sc);
        lenient().when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn(null);

        RuleVersionSnapshot.PreGateConfig timeWindow = new RuleVersionSnapshot.PreGateConfig(
                "TIME_WINDOW", Map.of("fromEpochMilli", 2000L, "toEpochMilli", 1000L));
        assertThatThrownBy(() -> publishService.createDraft(1L, "PAYMENT", "rule.tw",
                new RuleContent("时段规则", "AST_BOOLEAN",
                        new AstBody(new AndNode(List.of(), null, null)),
                        List.of(), List.of(timeWindow), List.of()),
                "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromEpochMilli 必须 <= toEpochMilli");
        verify(ruleVersionMapper, never()).insert((RuleVersion) any());
    }

    @Test
    void createDraft_timeWindowValid_passes() {
        SceneDef sc = new SceneDef();
        sc.setId(5L); sc.setTenantId(1L); sc.setCode("PAYMENT");
        sc.setEventTypes(List.of("payment.initiated"));
        when(sceneMapper.findByCode(any(), any())).thenReturn(sc);
        when(ruleDefinitionMapper.findByTenantAndCode(any(), any())).thenReturn(null);
        doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(20L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));

        RuleVersionSnapshot.PreGateConfig timeWindow = new RuleVersionSnapshot.PreGateConfig(
                "TIME_WINDOW", Map.of("fromEpochMilli", 1000L, "toEpochMilli", 2000L));
        DraftCreatedResult r = publishService.createDraft(1L, "PAYMENT", "rule.tw",
                new RuleContent("时段规则", "AST_BOOLEAN",
                        new AstBody(new AndNode(List.of(), null, null)),
                        List.of(), List.of(timeWindow), List.of()),
                "actor");

        assertThat(r.status()).isEqualTo("DRAFT");
        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(cap.capture());
        assertThat(cap.getValue().getPreGates()).hasSize(1);
    }

    @Test
    void publish_disabledRule_throws() {
        // DISABLED 规则须先 enable 再发布，不允许通过 publish 路径绕过 transitionStatus 状态机
        RuleDefinition disabledRule = new RuleDefinition();
        disabledRule.setId(10L); disabledRule.setTenantId(1L); disabledRule.setSceneCode("PAYMENT");
        disabledRule.setStatus(com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus.DISABLED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(disabledRule);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISABLED");
        verify(ruleVersionMapper, never()).findLatestDraft(any());
    }

}
