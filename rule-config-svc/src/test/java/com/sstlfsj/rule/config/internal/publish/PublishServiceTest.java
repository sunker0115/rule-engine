package com.sstlfsj.rule.config.internal.publish;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.internal.event.DraftCreatedSnapshot;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.event.RulePublishedSnapshot;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
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
        draftRule.setSceneId(5L);
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
        draftVersion.setConditionAst(new ConditionNode("c.type", "m.code", null, Map.of(), 0.0));
        draftVersion.setDecisionBindings(List.of());
        draftVersion.setPreGates(List.of());
        draftVersion.setStatus(RuleVersionStatus.DRAFT);
    }

    @Test
    void publish_rejectsWhenDecisionCodeNotFound() {
        // draft 绑定 REJECT,但 decision_definition 查不到 → DECISION_CODE_NOT_FOUND 拒绝
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("m.code"); md.setDataType("STRING"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(md));
        draftVersion.setDecisionBindings(List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 10)));
        when(decisionDefinitionMapper.findByCodes(eq(1L), anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECISION_CODE_NOT_FOUND");
    }

    @Test
    void publish_pullSceneWithDecisionActions_throws() {
        // D27:PULL Scene 下 Decision.actions 必须为空,绑了带 action 的 decision → 拒绝发布
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        scene.setDominantMode(DominantMode.PULL);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("m.code"); md.setDataType("STRING"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(md));
        draftVersion.setDecisionBindings(List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 0)));
        DecisionDefinition dd = new DecisionDefinition();
        dd.setTenantId(1L); dd.setCode("REJECT"); dd.setName("拒绝"); dd.setPriority(1);
        dd.setActions(List.of(new RuleVersionSnapshot.DecisionAction("a1", "SEND_ALERT", 0, Map.of())));
        when(decisionDefinitionMapper.findByCodes(eq(1L), anyCollection())).thenReturn(List.of(dd));

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PULL");
    }

    @Test
    void publish_freezesDecisionNameAndActionsIntoSnapshot() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("m.code"); md.setDataType("STRING"); md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(md));
        // 草稿期 binding priority 是占位 0；发布应从 decision_definition.priority 回填
        draftVersion.setDecisionBindings(List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 0)));
        DecisionDefinition dd = new DecisionDefinition();
        dd.setTenantId(1L); dd.setCode("REJECT"); dd.setName("拒绝"); dd.setPriority(10);
        dd.setActions(List.of(new RuleVersionSnapshot.DecisionAction("a1", "SEND_ALERT", 0, Map.of())));
        when(decisionDefinitionMapper.findByCodes(eq(1L), anyCollection())).thenReturn(List.of(dd));

        publishService.publish(1L, 10L, "actor");

        ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(cap.capture());
        RuleVersionSnapshot.DecisionBinding frozen = cap.getValue().getDecisionBindings().getFirst();
        assertThat(frozen.name()).isEqualTo("拒绝");
        assertThat(frozen.priority()).isEqualTo(10);   // 从 decision 回填，非草稿占位 0
        assertThat(frozen.actions()).hasSize(1);
        assertThat(frozen.actions().getFirst().actionType()).isEqualTo("SEND_ALERT");
    }

    @Test
    void publish_draftRule_createsVersionAndUpdatesDefinition() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        // 返回草稿 rule_version
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        // MyBatis-Plus 重载：用 (RuleVersion) 显式类型消除歧义
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        // B6：metric ACTIVE 行（version=null 兜底为 1）
        MetricDefinition mdMCode = new MetricDefinition();
        mdMCode.setMetricCode("m.code");
        mdMCode.setDataType("STRING");
        mdMCode.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(mdMCode));

        RuleVersionSnapshot snapshot = publishService.publish(1L, 10L, "operator1");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.sceneCode()).isEqualTo("PAYMENT");
        // v1 发布时 triggerEventTypes 为空列表（通配），精确路由在 eval-svc 侧处理
        assertThat(snapshot.triggerEventTypes()).isEmpty();
        // kind 从 rule_definition 流转到 snapshot
        assertThat(snapshot.kind()).isEqualTo("AST_BOOLEAN");
        // metricDependencies 由 AST 收集并冻结进 snapshot（B6 版本号由 ACTIVE 行读取，version 字段为 null 时兜底 1）
        assertThat(snapshot.metricDependencies())
                .containsExactly(new MetricDependency("m.code", 1));
        // 验证 rule_version 被插入，version=1，status=ACTIVE
        ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(rvCaptor.capture());
        assertThat(rvCaptor.getValue().getVersion()).isEqualTo(1L);
        assertThat(rvCaptor.getValue().getStatus()).isEqualTo(RuleVersionStatus.ACTIVE);
        // 验证 rule_definition 状态更新为 PUBLISHED
        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo(RuleDefinitionStatus.PUBLISHED);
        // 发布现在发两个事件：审计事件（PUBLISH）+ Modulith RulePublishedEvent
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        List<Object> events = eventCaptor.getAllValues();
        OperationAuditedEvent audit = events.stream()
                .filter(OperationAuditedEvent.class::isInstance)
                .map(OperationAuditedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(audit.action()).isEqualTo("PUBLISH");
        // PUBLISH 为非创建发布点：before 仍为 null，after 为 typed RulePublishedSnapshot
        assertThat(audit.beforeSnapshot()).isNull();
        assertThat(audit.afterSnapshot()).isInstanceOf(RulePublishedSnapshot.class);
        RulePublishedEvent published = events.stream()
                .filter(RulePublishedEvent.class::isInstance)
                .map(RulePublishedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(published.sceneCode()).isEqualTo("PAYMENT");
    }

    @Test
    void publish_nonDraftRule_throwsIllegalState() {
        draftRule.setStatus(RuleDefinitionStatus.PUBLISHED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有 DRAFT 状态的规则可以发布");
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
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(null);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有找到草稿版本");
    }

    @Test
    void publish_scorecard_非ScorecardRootNode根节点_抛异常() {
        // kind=SCORECARD，但 conditionAst 是 ConditionNode（非 ScorecardRootNode）
        draftRule.setKind(RuleKind.SCORECARD);
        draftVersion.setConditionAst(new ConditionNode("c.type", "m.code", null, Map.of(), 1.0));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ScorecardRootNode");
    }

    @Test
    void publish_scorecard_weight为零_抛异常() {
        // kind=SCORECARD，ScorecardRootNode 包含 weight=0 的 ConditionNode
        draftRule.setKind(RuleKind.SCORECARD);
        ConditionNode zeroWeightLeaf = new ConditionNode("c.type", "m.code", null, Map.of(), 0.0);
        draftVersion.setConditionAst(new ScorecardRootNode(List.of(zeroWeightLeaf), 60.0));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight 必须 > 0");
    }

    @Test
    void publish_decisionTree_conditionContainsXor_throws() {
        // kind=DECISION_TREE，IfNode 条件含 XorNode（决策树不支持 XOR）→ 发布期拒绝，避免上线后运行时 NO_EVALUATOR
        draftRule.setKind(RuleKind.DECISION_TREE);
        ConditionNode leaf = new ConditionNode("GTE", "m.code", null, Map.of("threshold", 0), null);
        IfNode root = new IfNode(new XorNode(List.of(leaf), null),
                new DecisionLeafNode("PASS", "PASS"), null);
        draftVersion.setConditionAst(root);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XOR");
    }

    @Test
    void publish_scorecard_weight为null_抛异常() {
        // weight=null 视为未设置，同样不允许发布（SCORECARD 必须填 weight>0）
        draftRule.setKind(RuleKind.SCORECARD);
        ConditionNode nullWeightLeaf = new ConditionNode("c.type", "m.code", null, Map.of(), null);
        draftVersion.setConditionAst(new ScorecardRootNode(List.of(nullWeightLeaf), 60.0));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight 必须 > 0");
    }

    @Test
    void createDraft_insertsRuleDefinitionAndVersion() {
        SceneDef draftScene = new SceneDef();
        draftScene.setId(5L);
        draftScene.setTenantId(1L);
        draftScene.setCode("risk.transfer");
        when(sceneMapper.findByCode(any(), any())).thenReturn(draftScene);

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


        DraftCreatedResult result = publishService.createDraft(
                1L, "risk.transfer", "rule.test", "测试规则",
                new com.sstlfsj.rule.kernel.api.model.ast.AndNode(java.util.List.of(), null, null),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), "SCORECARD", "actor1");

        assertThat(result.ruleDefinitionId()).isEqualTo(10L);
        assertThat(result.ruleVersionId()).isEqualTo(20L);
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo("DRAFT");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).insert(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo(RuleDefinitionStatus.DRAFT);
        assertThat(rdCaptor.getValue().getCode()).isEqualTo("rule.test");
        assertThat(rdCaptor.getValue().getKind()).isEqualTo(RuleKind.SCORECARD);

        ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(rvCaptor.capture());
        assertThat(rvCaptor.getValue().getVersion()).isEqualTo(1L);
        assertThat(rvCaptor.getValue().getStatus()).isEqualTo(RuleVersionStatus.DRAFT);
        assertThat(rvCaptor.getValue().getKind()).isEqualTo(RuleKind.SCORECARD);
        // conditionAst 直传 typed 落库（无 JSON 串来回）
        assertThat(rvCaptor.getValue().getConditionAst())
                .isInstanceOf(com.sstlfsj.rule.kernel.api.model.ast.AndNode.class);

        // CREATE 类审计：before/after 为同一个 typed DraftCreatedSnapshot 实例
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        OperationAuditedEvent audit = eventCaptor.getAllValues().stream()
                .filter(OperationAuditedEvent.class::isInstance)
                .map(OperationAuditedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(audit.action()).isEqualTo("CREATE");
        assertThat(audit.beforeSnapshot()).isSameAs(audit.afterSnapshot());
        assertThat(audit.afterSnapshot()).isEqualTo(new DraftCreatedSnapshot(10L, 20L));
    }

    @Test
    void createDraft_sceneNotFound_throwsIllegalArgument() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () ->
                publishService.createDraft(1L, "nonexistent", "rule.test", "测试",
                        null, null, null, null, null, "actor1"));
    }

    @Test
    void createDraft_duplicateCode_throwsIllegalArgument() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("risk.transfer");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);
        // 模拟同 tenant+scene 下已存在同 code 的规则
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(new RuleDefinition());

        assertThrows(IllegalArgumentException.class, () ->
                publishService.createDraft(1L, "risk.transfer", "rule.test", "测试",
                        null, null, null, null, null, "actor1"));

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
                1L, "risk.transfer", "rule.test", "测试规则",
                null, null, null, null, "EXPRESSION_SCRIPT", "actor1"))
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
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn((RuleDefinition) null);

        doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(20L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));

        publishService.createDraft(1L, "risk.transfer", "rule.test", "测试规则",
                new com.sstlfsj.rule.kernel.api.model.ast.AndNode(java.util.List.of(), null, null),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null, "actor1");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).insert(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getKind()).isEqualTo(RuleKind.AST_BOOLEAN);
    }

    @Test
    void publish_triggerEventType不在Scene白名单_抛IllegalArgument() {
        draftVersion.setTriggerEventTypes(List.of("order.placed"));
        scene.setEventTypes(java.util.List.of("payment.initiated"));   // 只允许 payment 类型

        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order.placed");
    }

    @Test
    void publish_triggerEventType在Scene白名单内_正常发布() {
        draftVersion.setTriggerEventTypes(List.of("payment.initiated"));
        scene.setEventTypes(java.util.List.of("payment.initiated", "payment.refunded"));
        draftVersion.setConditionAst(new ConditionNode("EQ", "metric1", null, Map.of(), 0.0));

        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        MetricDefinition mdMetric1 = new MetricDefinition();
        mdMetric1.setMetricCode("metric1");
        mdMetric1.setDataType("LONG");
        mdMetric1.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(mdMetric1));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        // 不应抛异常，发布成功
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_triggerEventTypes为空_跳过校验() {
        draftVersion.setTriggerEventTypes(List.of());
        scene.setEventTypes(java.util.List.of("payment.initiated"));
        draftVersion.setConditionAst(new ConditionNode("EQ", "m1", null, Map.of(), 0.0));

        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        MetricDefinition mdM1a = new MetricDefinition();
        mdM1a.setMetricCode("m1");
        mdM1a.setDataType("LONG");
        mdM1a.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(mdM1a));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        // 空 triggerEventTypes 应跳过校验，正常发布
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_sceneEventTypes为空_跳过校验() {
        // scene.eventTypes 为空（Scene 尚未配置白名单），发布不应被阻断
        draftVersion.setTriggerEventTypes(List.of("payment.initiated"));
        scene.setEventTypes(java.util.List.of());
        draftVersion.setConditionAst(new ConditionNode("EQ", "m1", null, Map.of(), 0.0));

        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        MetricDefinition mdM1b = new MetricDefinition();
        mdM1b.setMetricCode("m1");
        mdM1b.setDataType("LONG");
        mdM1b.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(mdM1b));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_unsupportedKind_throwsIllegalArgument() {
        // EXPRESSION_SCRIPT 是合法 RuleKind，但 publish 不在支持集内，应被拒
        draftRule.setKind(RuleKind.EXPRESSION_SCRIPT);
        draftVersion.setConditionAst(new ConditionNode("EQ", "m1", null, Map.of(), 0.0));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的规则 kind");
    }

    @Test
    void publish_decisionTreeKind_正常通过() {
        draftRule.setKind(RuleKind.DECISION_TREE);
        // 合法 IfNode：condition + thenBranch 均不为 null
        draftVersion.setConditionAst(new IfNode(
                new ConditionNode("GT", "amount", null, Map.of(), 0.0),
                new DecisionLeafNode("BLOCK", "HIGH_RISK"),
                new DecisionLeafNode("PASS", "LOW_RISK")));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        MetricDefinition mdAmount1 = new MetricDefinition();
        mdAmount1.setMetricCode("amount");
        mdAmount1.setDataType("LONG");
        mdAmount1.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(mdAmount1));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_decisionTableKind_正常通过() {
        draftRule.setKind(RuleKind.DECISION_TABLE);
        // 合法 DecisionTableNode：1 列 1 行，行列数一致
        draftVersion.setConditionAst(new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT")),
                List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK"))));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        MetricDefinition mdAmount2 = new MetricDefinition();
        mdAmount2.setMetricCode("amount");
        mdAmount2.setDataType("LONG");
        mdAmount2.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(mdAmount2));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_decisionTree_非IfNode根节点_抛异常() {
        draftRule.setKind(RuleKind.DECISION_TREE);
        // 根节点是 ConditionNode，不是 IfNode
        draftVersion.setConditionAst(new ConditionNode("GT", "amount", null, Map.of(), 0.0));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IfNode");
    }

    @Test
    void publish_decisionTree_thenBranchNull_抛异常() {
        draftRule.setKind(RuleKind.DECISION_TREE);
        // thenBranch = null
        draftVersion.setConditionAst(new IfNode(
                new ConditionNode("GT", "amount", null, Map.of(), 0.0),
                null, null));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thenBranch");
    }

    @Test
    void publish_decisionTable_非DecisionTableNode根节点_抛异常() {
        draftRule.setKind(RuleKind.DECISION_TABLE);
        draftVersion.setConditionAst(new ConditionNode("GT", "amount", null, Map.of(), 0.0));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DecisionTableNode");
    }

    @Test
    void publish_decisionTable_行列数不一致_抛异常() {
        draftRule.setKind(RuleKind.DECISION_TABLE);
        // 2 列但行只有 1 个条件值
        draftVersion.setConditionAst(new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT"),
                        new DecisionTableNode.Column("count", "LT")),
                List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK"))));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列数");
    }

    @Test
    void publish_decisionTable_columns为空_抛异常() {
        draftRule.setKind(RuleKind.DECISION_TABLE);
        draftVersion.setConditionAst(new DecisionTableNode(
                List.of(),
                List.of(new DecisionTableNode.Row(List.of(), "BLOCK"))));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columns");
    }

    @Test
    void publish_decisionTable_rows为空_抛异常() {
        draftRule.setKind(RuleKind.DECISION_TABLE);
        draftVersion.setConditionAst(new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT")),
                List.of()));
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
    }

    @Test
    void publish_rolloutPercentageOutOfRange_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        draftVersion.setTriggerEventTypes(List.of());
        draftVersion.setPreGates(List.of(new PreGateConfig("ROLLOUT", Map.of("percentage", 101))));
        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("percentage");
    }

    @Test
    void publish_rolloutInvalidRange_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        draftVersion.setTriggerEventTypes(List.of());
        draftVersion.setPreGates(List.of(
                new PreGateConfig("ROLLOUT", Map.of("bucketStart", 60, "bucketEnd", 50))));
        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void publish_rolloutBlankExperimentId_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        draftVersion.setTriggerEventTypes(List.of());
        draftVersion.setPreGates(List.of(
                new PreGateConfig("ROLLOUT", Map.of("percentage", 50, "experimentId", "  "))));
        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("experimentId");
    }

    @Test
    void publish_unregisteredGateType_throws() {
        // pre-gate 收敛:仅 ROLLOUT 合法,已砍的 RATE_LIMIT/MUTEX 等配置一律拒绝发布
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        draftVersion.setTriggerEventTypes(List.of());
        draftVersion.setPreGates(List.of(new PreGateConfig("RATE_LIMIT", Map.of("limit", 10))));
        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateType");
    }

    @Test
    void publish_rolloutValidRange_publishes() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        MetricDefinition mdRollout = new MetricDefinition();
        mdRollout.setMetricCode("m.code");
        mdRollout.setDataType("STRING");
        mdRollout.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(java.util.List.of(mdRollout));
        draftVersion.setTriggerEventTypes(List.of());
        draftVersion.setPreGates(List.of(
                new PreGateConfig("ROLLOUT", Map.of("experimentId", "exp-1", "bucketStart", 0, "bucketEnd", 50))));
        assertThat(publishService.publish(1L, 10L, "actor")).isNotNull();
    }

    @Test
    void publish_freezesDataTypeInConditionAst() {
        // 发布后 condition_ast 里的 ConditionNode 应含 dataType
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        // AST: GT 算子，metricCode="amount"
        draftVersion.setConditionAst(new ConditionNode("GT", "amount", null,
                Map.of("threshold", 100), 0.0));

        // metric_definition 返回 amount -> LONG
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("amount");
        md.setDataType("LONG");
        when(metricDefinitionMapper.findActiveByCodes(any(), any()))
                .thenReturn(java.util.List.of(md));

        publishService.publish(1L, 10L, "op");

        // 验证 conditionAst 写入的是 resolvedAst（含 dataType），而非 draft 原始节点
        ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(rvCaptor.capture());
        AstNode written = rvCaptor.getValue().getConditionAst();
        assertThat(written).isInstanceOf(ConditionNode.class);
        assertThat(((ConditionNode) written).dataType()).isEqualTo("LONG");
    }

    @Test
    void publish_incompatibleOperatorDataType_throwsIllegalArgument() {
        // GT 算子但 metric dataType=BOOLEAN -> 发布期报错
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        draftVersion.setConditionAst(new ConditionNode("GT", "flag", null,
                Map.of("threshold", "true"), 0.0));

        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("flag");
        md.setDataType("BOOLEAN");
        when(metricDefinitionMapper.findActiveByCodes(any(), any()))
                .thenReturn(java.util.List.of(md));

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT")
                .hasMessageContaining("BOOLEAN");
    }

    @Test
    void publish_sqlMetricWithDbTimeFunction_throws() {
        // SQL_AGGREGATE metric 的 SQL 含 NOW() → 发布期安全校验拒绝（B21）
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        draftVersion.setConditionAst(new ConditionNode("GT", "balance", null, Map.of("threshold", 1), 0.0));

        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("balance");
        md.setDataType("LONG");
        md.setSourceType("SQL_AGGREGATE");
        md.setParams(java.util.Map.of("datasource", "ro", "sql", "SELECT 1 WHERE t >= NOW() - INTERVAL 7 DAY"));
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOW");
    }

    @Test
    void publish_freezesActiveMetricVersion_inMetricDependencies() {
        // B6：发布引用 account.age（ACTIVE version=3）的规则，快照 metricDependencies 应含冻结的版本号
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        // AST 引用 account.age
        draftVersion.setConditionAst(new ConditionNode("GT", "account.age", null, Map.of("threshold", 30), 0.0));

        // metric_definition 返回 account.age ACTIVE version=3
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("account.age");
        md.setDataType("LONG");
        md.setVersion(3);
        md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        RuleVersionSnapshot snapshot = publishService.publish(1L, 10L, "op");

        // 快照中 metricDependencies 应冻结版本号 3
        assertThat(snapshot.metricDependencies())
                .containsExactly(new MetricDependency("account.age", 3));
    }

    @Test
    void publish_multipleActiveVersions_throwsDataAnomaly() {
        // B6 兜底：同一 metricCode 存在两行 ACTIVE（数据异常）→ 发布拒绝
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        draftVersion.setConditionAst(new ConditionNode("GT", "account.age", null, Map.of("threshold", 30), 0.0));

        // 同 code 两行 ACTIVE，版本不同
        MetricDefinition mdV2 = new MetricDefinition();
        mdV2.setMetricCode("account.age");
        mdV2.setDataType("LONG");
        mdV2.setVersion(2);
        mdV2.setStatus(MetricStatus.ACTIVE);
        MetricDefinition mdV3 = new MetricDefinition();
        mdV3.setMetricCode("account.age");
        mdV3.setDataType("LONG");
        mdV3.setVersion(3);
        mdV3.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(mdV2, mdV3));

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数据异常")
                .hasMessageContaining("account.age");
    }

    @Test
    void publish_referencedMetricHasNoActiveVersion_throwsIllegalArgument() {
        // B6：被引用的 metric 无 ACTIVE 行 → 发布拒绝
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(draftVersion);

        // AST 引用 account.age
        draftVersion.setConditionAst(new ConditionNode("GT", "account.age", null, Map.of("threshold", 30), 0.0));

        // metric_definition 查询返回空（无 ACTIVE 版本）
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account.age")
                .hasMessageContaining("ACTIVE");
    }
}
