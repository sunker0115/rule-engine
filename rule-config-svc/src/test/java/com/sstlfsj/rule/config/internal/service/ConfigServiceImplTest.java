package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigServiceImplTest {

    @Mock PublishService publishService;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks ConfigServiceImpl configService;

    @Test
    void publish_delegates_to_publishService() {
        RuleVersionSnapshot expected = new RuleVersionSnapshot(
                42L, "PAYMENT", "1",
                new ConditionNode("c.type", null, null, Map.of(), 0.0),
                List.of(), List.of(), null, null
        );
        when(publishService.publish(1L, 10L, "actor1")).thenReturn(expected);

        RuleVersionSnapshot result = configService.publish("1", 10L, "actor1");

        assertThat(result.ruleVersionId()).isEqualTo(42L);
        verify(publishService).publish(1L, 10L, "actor1");
    }

    @Test
    void disable_updatesStatusAndPublishesAuditEvent() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        rule.setStatus(RuleDefinitionStatus.PUBLISHED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        configService.disable("1", 10L, "actor1");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo(RuleDefinitionStatus.DISABLED);

        ArgumentCaptor<OperationAuditedEvent> evCaptor =
                ArgumentCaptor.forClass(OperationAuditedEvent.class);
        verify(eventPublisher).publishEvent(evCaptor.capture());
        OperationAuditedEvent ev = evCaptor.getValue();
        assertThat(ev.action()).isEqualTo("DISABLE");
        assertThat(ev.targetType()).isEqualTo("rule_definition");
        assertThat(ev.targetId()).isEqualTo("10");
        assertThat(ev.actorType()).isEqualTo("USER");
        // before 记禁用前状态(PUBLISHED)，after 记 DISABLED(审计完整性)
        var before = (com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot) ev.beforeSnapshot();
        var after = (com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot) ev.afterSnapshot();
        assertThat(before.status()).isEqualTo("PUBLISHED");
        assertThat(after.status()).isEqualTo("DISABLED");
    }

    @Test
    void listRules_withSceneCodeAndStatus_filtersAndReturnsPage() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("risk.transfer");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);

        RuleDefinition rd = new RuleDefinition();
        rd.setId(10L);
        rd.setCode("rule.a");
        rd.setName("规则A");
        rd.setStatus(RuleDefinitionStatus.PUBLISHED);
        rd.setCurrentVersion(42L);
        rd.setPublishedAt(java.time.LocalDateTime.of(2026, 6, 1, 0, 0));

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RuleDefinition> mockPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
        mockPage.setRecords(java.util.List.of(rd));
        when(ruleDefinitionMapper.selectRulePage(any(), any(), any(), any())).thenReturn(mockPage);

        var result = configService.listRules("1", "risk.transfer", "PUBLISHED", 1, 20);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(1);
        var item = result.getRecords().get(0);
        assertThat(item.ruleDefinitionId()).isEqualTo(10L);
        assertThat(item.code()).isEqualTo("rule.a");
        assertThat(item.status()).isEqualTo("PUBLISHED");
        assertThat(item.currentVersion()).isEqualTo(42L);
        verify(sceneMapper).findByCode(any(), any());
        verify(ruleDefinitionMapper).selectRulePage(any(), any(), any(), any());
    }

    @Test
    void listRules_sceneNotFound_returnsEmptyPage() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(null);

        var result = configService.listRules("1", "nonexistent.scene", null, 1, 20);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(0);
        verifyNoInteractions(ruleDefinitionMapper);
    }

    @Test
    void listRules_noSceneCodeFilter_queriesAllRulesForTenant() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RuleDefinition> emptyPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 0);
        emptyPage.setRecords(java.util.List.of());
        when(ruleDefinitionMapper.selectRulePage(any(), any(), any(), any())).thenReturn(emptyPage);

        var result = configService.listRules("1", null, null, 1, 20);

        assertThat(result.getRecords()).isEmpty();
        verify(ruleDefinitionMapper).selectRulePage(any(), any(), any(), any());
        verifyNoInteractions(sceneMapper);
    }

    @Test
    void createDraft_delegatesToPublishService() {
        DraftCreatedResult expected = new DraftCreatedResult(1L, 2L, 1L, "DRAFT");
        var ast = new com.sstlfsj.rule.kernel.api.model.ast.AndNode(java.util.List.of(), null, null);
        when(publishService.createDraft(1L, "risk.transfer", "rule.a", "规则A",
                ast, java.util.List.of(), java.util.List.of(), java.util.List.of(), "AST_BOOLEAN", "actor1"))
                .thenReturn(expected);

        DraftCreatedResult result = configService.createDraft("1", "risk.transfer",
                "rule.a", "规则A", ast, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                "AST_BOOLEAN", "actor1");

        assertThat(result.ruleDefinitionId()).isEqualTo(1L);
        verify(publishService).createDraft(1L, "risk.transfer", "rule.a", "规则A",
                ast, java.util.List.of(), java.util.List.of(), java.util.List.of(), "AST_BOOLEAN", "actor1");
    }

    @Test
    void getRuleDetail_组装定义与ACTIVE版本() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        rule.setSceneId(5L);
        rule.setCode("rule.a");
        rule.setName("规则A");
        rule.setStatus(RuleDefinitionStatus.PUBLISHED);
        rule.setKind(RuleKind.AST_BOOLEAN);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);

        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setCode("risk.transfer");
        when(sceneMapper.selectById(5L)).thenReturn(scene);

        RuleVersion active = new RuleVersion();
        active.setId(42L);
        active.setConditionAst(new AndNode(List.of(), null, null));
        active.setDecisionBindings(List.of(new DecisionBinding("BLOCK", 100)));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(active);

        RuleDetailVO vo = configService.getRuleDetail("1", 10L);

        assertThat(vo.ruleDefinitionId()).isEqualTo(10L);
        assertThat(vo.code()).isEqualTo("rule.a");
        assertThat(vo.sceneCode()).isEqualTo("risk.transfer");
        assertThat(vo.currentVersionId()).isEqualTo(42L);
        assertThat(vo.conditionAst()).isInstanceOf(AndNode.class);
        assertThat(vo.decisionBindings()).hasSize(1);
        assertThat(vo.decisionBindings().get(0).decisionCode()).isEqualTo("BLOCK");
        assertThat(vo.decisionBindings().get(0).priority()).isEqualTo(100);
    }

    @Test
    void getRuleDetail_规则不存在_抛IllegalArgument() {
        when(ruleDefinitionMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> configService.getRuleDetail("1", 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规则不存在");
    }

    @Test
    void editDraft_validKind_delegatesWithParsedRuleKind() {
        when(publishService.editDraft(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new DraftCreatedResult(10L, 20L, 1L, "DRAFT"));

        configService.editDraft("1", 10L, "名", "AST_BOOLEAN",
                null, null, null, null, "actor");

        // kind 字符串 "AST_BOOLEAN" 解析为枚举后透传 publishService
        verify(publishService).editDraft(eq(1L), eq(10L), eq("名"), eq(RuleKind.AST_BOOLEAN),
                any(), any(), any(), any(), eq("actor"));
    }

    @Test
    void editDraft_invalidKind_throwsBeforeDelegating() {
        // 非法 kind 在 parseKind 阶段即拒，不触达 publishService
        assertThatThrownBy(() -> configService.editDraft("1", 10L, "名", "BOGUS_KIND",
                null, null, null, null, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的规则 kind");
        verifyNoInteractions(publishService);
    }

    @Test
    void newVersion_validKind_delegatesWithParsedRuleKind() {
        when(publishService.newVersion(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new DraftCreatedResult(10L, 30L, 2L, "DRAFT"));

        configService.newVersion("1", 10L, "名", "AST_BOOLEAN",
                null, null, null, null, 50L, "actor");

        // kind 字符串 "AST_BOOLEAN" 解析为枚举后透传 publishService，fromVersionId 原样透传
        verify(publishService).newVersion(eq(1L), eq(10L), eq("名"), eq(RuleKind.AST_BOOLEAN),
                any(), any(), any(), any(), eq(50L), eq("actor"));
    }

    @Test
    void newVersion_invalidKind_throwsBeforeDelegating() {
        // 非法 kind 在 parseKind 阶段即拒，不触达 publishService
        assertThatThrownBy(() -> configService.newVersion("1", 10L, "名", "BOGUS_KIND",
                null, null, null, null, null, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的规则 kind");
        verifyNoInteractions(publishService);
    }
}
