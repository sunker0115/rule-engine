package com.sstlfsj.rule.config.internal.publish;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
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

@ExtendWith(MockitoExtension.class)
class PublishServiceTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Mock AuditLogMapper auditLogMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AstSerializer astSerializer;

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
        draftRule.setStatus("DRAFT");
        draftRule.setKind("AST_BOOLEAN");

        scene = new SceneDef();
        scene.setId(5L);
        scene.setCode("PAYMENT");
        scene.setEventTypes("[\"payment.initiated\"]");
        scene.setStatus("ACTIVE");

        draftVersion = new RuleVersion();
        draftVersion.setId(100L);
        draftVersion.setRuleDefinitionId(10L);
        draftVersion.setVersion(0L);
        draftVersion.setConditionAst("{\"type\":\"ConditionNode\"}");
        draftVersion.setDecisionBindings("[]");
        draftVersion.setPreGates("[]");
        draftVersion.setRollout("{}");
        draftVersion.setStatus("DRAFT");
    }

    @Test
    void publish_draftRule_createsVersionAndUpdatesDefinition() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        // 返回草稿 rule_version
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        // MyBatis-Plus 重载：用 (RuleVersion) 显式类型消除歧义
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);
        ConditionNode fakeAst = new ConditionNode("c.type", "m.code", null, Map.of(), 0.0);
        when(astSerializer.fromJson(anyString())).thenReturn(fakeAst);

        RuleVersionSnapshot snapshot = publishService.publish(1L, 10L, "operator1");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.sceneCode()).isEqualTo("PAYMENT");
        // v1 发布时 triggerEventTypes 为空列表（通配），精确路由在 eval-svc 侧处理
        assertThat(snapshot.triggerEventTypes()).isEmpty();
        // kind 从 rule_definition 流转到 snapshot
        assertThat(snapshot.kind()).isEqualTo("AST_BOOLEAN");
        // 验证 rule_version 被插入，version=1，status=ACTIVE
        ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(rvCaptor.capture());
        assertThat(rvCaptor.getValue().getVersion()).isEqualTo(1L);
        assertThat(rvCaptor.getValue().getStatus()).isEqualTo("ACTIVE");
        // 验证 rule_definition 状态更新为 PUBLISHED
        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo("PUBLISHED");
        // 验证审计日志写入
        ArgumentCaptor<AuditLog> logCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getAction()).isEqualTo("PUBLISH");
        // 验证 Modulith 事件发布，sceneCode 匹配（用 Object 重载避免 ApplicationEvent 歧义）
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(RulePublishedEvent.class);
        assertThat(((RulePublishedEvent) eventCaptor.getValue()).sceneCode()).isEqualTo("PAYMENT");
    }

    @Test
    void publish_nonDraftRule_throwsIllegalState() {
        draftRule.setStatus("PUBLISHED");
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
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有找到草稿版本");
    }

    @Test
    void publish_scorecard_非ScorecardRootNode根节点_抛异常() {
        // kind=SCORECARD，但 conditionAst 反序列化结果是 ConditionNode（非 ScorecardRootNode）
        draftRule.setKind("SCORECARD");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        ConditionNode wrongRoot = new ConditionNode("c.type", "m.code", null, Map.of(), 1.0);
        when(astSerializer.fromJson(anyString())).thenReturn(wrongRoot);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ScorecardRootNode");
    }

    @Test
    void publish_scorecard_weight为零_抛异常() {
        // kind=SCORECARD，ScorecardRootNode 包含 weight=0 的 ConditionNode
        draftRule.setKind("SCORECARD");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        ConditionNode zeroWeightLeaf = new ConditionNode("c.type", "m.code", null, Map.of(), 0.0);
        ScorecardRootNode scorecardRoot = new ScorecardRootNode(List.of(zeroWeightLeaf), 60.0);
        when(astSerializer.fromJson(anyString())).thenReturn(scorecardRoot);

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
        when(sceneMapper.selectOne(any())).thenReturn(draftScene);

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

        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        DraftCreatedResult result = publishService.createDraft(
                1L, "risk.transfer", "rule.test", "测试规则",
                "{\"type\":\"AndNode\"}", "[]", "[]", "[]", "SCORECARD", "actor1");

        assertThat(result.ruleDefinitionId()).isEqualTo(10L);
        assertThat(result.ruleVersionId()).isEqualTo(20L);
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo("DRAFT");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).insert(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(rdCaptor.getValue().getCode()).isEqualTo("rule.test");
        assertThat(rdCaptor.getValue().getKind()).isEqualTo("SCORECARD");

        ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(rvCaptor.capture());
        assertThat(rvCaptor.getValue().getVersion()).isEqualTo(1L);
        assertThat(rvCaptor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(rvCaptor.getValue().getKind()).isEqualTo("SCORECARD");
    }

    @Test
    void createDraft_sceneNotFound_throwsIllegalArgument() {
        when(sceneMapper.selectOne(any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () ->
                publishService.createDraft(1L, "nonexistent", "rule.test", "测试",
                        "{}", "[]", "[]", "[]", null, "actor1"));
    }

    @Test
    void createDraft_duplicateCode_throwsIllegalArgument() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("risk.transfer");
        when(sceneMapper.selectOne(any())).thenReturn(scene);
        // 模拟同 tenant+scene 下已存在同 code 的规则
        when(ruleDefinitionMapper.selectCount(any())).thenReturn(1L);

        assertThrows(IllegalArgumentException.class, () ->
                publishService.createDraft(1L, "risk.transfer", "rule.test", "测试",
                        "{}", "[]", "[]", "[]", null, "actor1"));

        verify(ruleDefinitionMapper, never()).insert(any(RuleDefinition.class));
    }

    @Test
    void createDraft_invalidKind_throwsIllegalArgument() {
        SceneDef draftScene = new SceneDef();
        draftScene.setId(5L);
        draftScene.setTenantId(1L);
        draftScene.setCode("risk.transfer");
        when(sceneMapper.selectOne(any())).thenReturn(draftScene);

        assertThatThrownBy(() -> publishService.createDraft(
                1L, "risk.transfer", "rule.test", "测试规则",
                "{}", "[]", "[]", "[]", "EXPRESSION_SCRIPT", "actor1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的规则 kind");
    }

    @Test
    void createDraft_nullKind_defaultsToAstBoolean() {
        SceneDef draftScene = new SceneDef();
        draftScene.setId(5L);
        draftScene.setTenantId(1L);
        draftScene.setCode("risk.transfer");
        when(sceneMapper.selectOne(any())).thenReturn(draftScene);
        when(ruleDefinitionMapper.selectCount(any())).thenReturn(0L);

        doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(20L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        publishService.createDraft(1L, "risk.transfer", "rule.test", "测试规则",
                "{}", "[]", "[]", "[]", null, "actor1");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).insert(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getKind()).isEqualTo("AST_BOOLEAN");
    }

    @Test
    void publish_triggerEventType不在Scene白名单_抛IllegalArgument() {
        draftVersion.setTriggerEventTypes("[\"order.placed\"]");
        scene.setEventTypes("[\"payment.initiated\"]");   // 只允许 payment 类型

        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order.placed");
    }

    @Test
    void publish_triggerEventType在Scene白名单内_正常发布() {
        draftVersion.setTriggerEventTypes("[\"payment.initiated\"]");
        scene.setEventTypes("[\"payment.initiated\",\"payment.refunded\"]");

        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(astSerializer.fromJson(any()))
                .thenReturn(new ConditionNode("EQ", "metric1", null, Map.of(), 0.0));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        // 不应抛异常，发布成功
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_triggerEventTypes为空_跳过校验() {
        draftVersion.setTriggerEventTypes("[]");
        scene.setEventTypes("[\"payment.initiated\"]");

        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(astSerializer.fromJson(any()))
                .thenReturn(new ConditionNode("EQ", "m1", null, Map.of(), 0.0));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        // 空 triggerEventTypes 应跳过校验，正常发布
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_sceneEventTypes为空_跳过校验() {
        // scene.eventTypes 为空（Scene 尚未配置白名单），发布不应被阻断
        draftVersion.setTriggerEventTypes("[\"payment.initiated\"]");
        scene.setEventTypes("[]");

        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(astSerializer.fromJson(any()))
                .thenReturn(new ConditionNode("EQ", "m1", null, Map.of(), 0.0));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_未知kind_抛IllegalArgument() {
        draftRule.setKind("UNKNOWN_KIND");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(astSerializer.fromJson(any()))
                .thenReturn(new ConditionNode("EQ", "m1", null, Map.of(), 0.0));

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的规则 kind");
    }

    @Test
    void publish_decisionTreeKind_正常通过() {
        draftRule.setKind("DECISION_TREE");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        // 合法 IfNode：condition + thenBranch 均不为 null
        when(astSerializer.fromJson(any()))
                .thenReturn(new IfNode(
                        new ConditionNode("GT", "amount", null, Map.of(), 0.0),
                        new DecisionLeafNode("BLOCK", "HIGH_RISK"),
                        new DecisionLeafNode("PASS", "LOW_RISK")));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_decisionTableKind_正常通过() {
        draftRule.setKind("DECISION_TABLE");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        // 合法 DecisionTableNode：1 列 1 行，行列数一致
        when(astSerializer.fromJson(any()))
                .thenReturn(new DecisionTableNode(
                        List.of(new DecisionTableNode.Column("amount", "GT")),
                        List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK"))));
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> publishService.publish(1L, 10L, "actor"));
    }

    @Test
    void publish_decisionTree_非IfNode根节点_抛异常() {
        draftRule.setKind("DECISION_TREE");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        // 根节点是 ConditionNode，不是 IfNode
        when(astSerializer.fromJson(anyString()))
                .thenReturn(new ConditionNode("GT", "amount", null, Map.of(), 0.0));

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IfNode");
    }

    @Test
    void publish_decisionTree_thenBranchNull_抛异常() {
        draftRule.setKind("DECISION_TREE");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        // thenBranch = null
        IfNode badTree = new IfNode(
                new ConditionNode("GT", "amount", null, Map.of(), 0.0),
                null, null);
        when(astSerializer.fromJson(anyString())).thenReturn(badTree);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thenBranch");
    }

    @Test
    void publish_decisionTable_非DecisionTableNode根节点_抛异常() {
        draftRule.setKind("DECISION_TABLE");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(astSerializer.fromJson(anyString()))
                .thenReturn(new ConditionNode("GT", "amount", null, Map.of(), 0.0));

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DecisionTableNode");
    }

    @Test
    void publish_decisionTable_行列数不一致_抛异常() {
        draftRule.setKind("DECISION_TABLE");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        // 2 列但行只有 1 个条件值
        DecisionTableNode table = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT"),
                        new DecisionTableNode.Column("count", "LT")),
                List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK")));
        when(astSerializer.fromJson(anyString())).thenReturn(table);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列数");
    }

    @Test
    void publish_decisionTable_columns为空_抛异常() {
        draftRule.setKind("DECISION_TABLE");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        DecisionTableNode emptyColumns = new DecisionTableNode(
                List.of(),
                List.of(new DecisionTableNode.Row(List.of(), "BLOCK")));
        when(astSerializer.fromJson(anyString())).thenReturn(emptyColumns);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columns");
    }

    @Test
    void publish_decisionTable_rows为空_抛异常() {
        draftRule.setKind("DECISION_TABLE");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        DecisionTableNode emptyRows = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT")),
                List.of());
        when(astSerializer.fromJson(anyString())).thenReturn(emptyRows);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
    }
}
