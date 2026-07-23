package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.dto.RuleListQuery;
import com.sstlfsj.rule.config.api.dto.RuleVersionContentVO;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.TenantMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.AstBody;
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
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock TenantMapper tenantMapper;
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

        RuleVersionSnapshot result = configService.publish(1L, 10L, "actor1");

        assertThat(result.ruleVersionId()).isEqualTo(42L);
        verify(publishService).publish(1L, 10L, "actor1");
    }

    @Test
    void disable_updatesStatus_publishesAudit_andIndexRefreshEvent() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        rule.setSceneCode("PAYMENT");
        rule.setCurrentVersion(100L);
        rule.setStatus(RuleDefinitionStatus.PUBLISHED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        configService.disable(1L, 10L, "actor1");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo(RuleDefinitionStatus.DISABLED);

        // 发两个事件：审计(DISABLE) + RulePublishedEvent(触发 eval 索引重建，配合 loader 的 rd.status 过滤摘除该规则)
        ArgumentCaptor<Object> evCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(evCaptor.capture());
        OperationAuditedEvent audit = evCaptor.getAllValues().stream()
                .filter(OperationAuditedEvent.class::isInstance).map(OperationAuditedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(audit.action()).isEqualTo(com.sstlfsj.rule.config.internal.domain.AuditAction.DISABLE);
        assertThat(audit.targetId()).isEqualTo("10");
        var before = (com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot) audit.beforeSnapshot();
        var after = (com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot) audit.afterSnapshot();
        assertThat(before.status()).isEqualTo("PUBLISHED");
        assertThat(after.status()).isEqualTo("DISABLED");
        com.sstlfsj.rule.config.api.event.RulePublishedEvent idx = evCaptor.getAllValues().stream()
                .filter(com.sstlfsj.rule.config.api.event.RulePublishedEvent.class::isInstance)
                .map(com.sstlfsj.rule.config.api.event.RulePublishedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(idx.sceneCode()).isEqualTo("PAYMENT");
        assertThat(idx.tenantId()).isEqualTo("1");
    }

    @Test
    void enable_disabledRule_togglesToPublished_publishesAudit_andIndexRefreshEvent() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        rule.setSceneCode("PAYMENT");
        rule.setCurrentVersion(100L);
        rule.setStatus(RuleDefinitionStatus.DISABLED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);

        configService.enable(1L, 10L, "actor1");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo(RuleDefinitionStatus.PUBLISHED);

        ArgumentCaptor<Object> evCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(evCaptor.capture());
        OperationAuditedEvent audit = evCaptor.getAllValues().stream()
                .filter(OperationAuditedEvent.class::isInstance).map(OperationAuditedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(audit.action()).isEqualTo(com.sstlfsj.rule.config.internal.domain.AuditAction.ENABLE);
        var before = (com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot) audit.beforeSnapshot();
        var after = (com.sstlfsj.rule.config.internal.event.RuleStatusSnapshot) audit.afterSnapshot();
        assertThat(before.status()).isEqualTo("DISABLED");
        assertThat(after.status()).isEqualTo("PUBLISHED");
        assertThat(evCaptor.getAllValues().stream()
                .anyMatch(com.sstlfsj.rule.config.api.event.RulePublishedEvent.class::isInstance))
                .as("enable 也应发 RulePublishedEvent 触发索引把规则装回").isTrue();
    }

    @Test
    void disable_draftRule_rejectedAndNoStatusChange() {
        // 守卫：disable 仅接受 PUBLISHED 源态，DRAFT 被拒（杜绝无 current_version 的脏 PUBLISHED）
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        rule.setStatus(RuleDefinitionStatus.DRAFT);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);

        assertThatThrownBy(() -> configService.disable(1L, 10L, "actor1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅 PUBLISHED 规则可禁用");
        verify(ruleDefinitionMapper, never()).updateById((RuleDefinition) any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void enable_publishedRule_rejected() {
        // enable 仅接受 DISABLED 源态，对已 PUBLISHED 规则启用被拒
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        rule.setStatus(RuleDefinitionStatus.PUBLISHED);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);

        assertThatThrownBy(() -> configService.enable(1L, 10L, "actor1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅 DISABLED 规则可启用");
        verify(ruleDefinitionMapper, never()).updateById((RuleDefinition) any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void listRules_withSceneCodeAndStatus_filtersAndReturnsPage() {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(10L);
        rd.setSceneCode("risk.transfer");
        rd.setCode("rule.a");
        rd.setName("规则A");
        rd.setStatus(RuleDefinitionStatus.PUBLISHED);
        rd.setKind(RuleKind.AST_BOOLEAN);
        rd.setCurrentVersion(42L);
        rd.setPublishedAt(java.time.LocalDateTime.of(2026, 6, 1, 0, 0));

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RuleDefinition> mockPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
        mockPage.setRecords(java.util.List.of(rd));
        when(ruleDefinitionMapper.selectRulePage(any(), any(), any(), any(), any(), any())).thenReturn(mockPage);

        var result = configService.listRules(new RuleListQuery(1L, "risk.transfer", "PUBLISHED", null, null, 1, 20));

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(1);
        var item = result.getRecords().get(0);
        assertThat(item.getId()).isEqualTo(10L);
        assertThat(item.getCode()).isEqualTo("rule.a");
        assertThat(item.getKind()).isEqualTo(RuleKind.AST_BOOLEAN);
        assertThat(item.getStatus()).isEqualTo(RuleDefinitionStatus.PUBLISHED);
        assertThat(item.getCurrentVersion()).isEqualTo(42L);
        // 翻译层已消灭：sceneCode 直传 selectRulePage，不再经 sceneMapper 转 sceneId
        verify(ruleDefinitionMapper).selectRulePage(
                any(), eq(1L), eq("risk.transfer"), eq("PUBLISHED"), any(), any());
    }

    @Test
    void listRules_sceneCodeWithNoRules_returnsEmptyPage() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RuleDefinition> emptyPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 0);
        emptyPage.setRecords(java.util.List.of());
        when(ruleDefinitionMapper.selectRulePage(any(), any(), any(), any(), any(), any())).thenReturn(emptyPage);

        var result = configService.listRules(new RuleListQuery(1L, "nonexistent.scene", null, null, null, 1, 20));

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(0);
        verify(ruleDefinitionMapper).selectRulePage(
                any(), eq(1L), eq("nonexistent.scene"), any(), any(), any());
    }

    @Test
    void listRules_noSceneCodeFilter_queriesAllRulesForTenant() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RuleDefinition> emptyPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 0);
        emptyPage.setRecords(java.util.List.of());
        when(ruleDefinitionMapper.selectRulePage(any(), any(), any(), any(), any(), any())).thenReturn(emptyPage);

        var result = configService.listRules(new RuleListQuery(1L, null, null, null, null, 1, 20));

        assertThat(result.getRecords()).isEmpty();
        verify(ruleDefinitionMapper).selectRulePage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createDraft_delegatesToPublishService() {
        DraftCreatedResult expected = new DraftCreatedResult(1L, 2L, 1L, "DRAFT");
        var ast = new com.sstlfsj.rule.kernel.api.model.ast.AndNode(java.util.List.of(), null, null);
        RuleContent content = new RuleContent("规则A", "AST_BOOLEAN", new AstBody(ast),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        when(publishService.createDraft(1L, "risk.transfer", "rule.a", content, "actor1"))
                .thenReturn(expected);

        DraftCreatedResult result = configService.createDraft(1L, "risk.transfer",
                "rule.a", content, "actor1");

        assertThat(result.ruleDefinitionId()).isEqualTo(1L);
        verify(publishService).createDraft(1L, "risk.transfer", "rule.a", content, "actor1");
    }

    @Test
    void getRuleDetail_组装定义与ACTIVE版本() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        rule.setSceneCode("risk.transfer");
        rule.setCode("rule.a");
        rule.setName("规则A");
        rule.setStatus(RuleDefinitionStatus.PUBLISHED);
        rule.setKind(RuleKind.AST_BOOLEAN);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);

        RuleVersion active = new RuleVersion();
        active.setId(42L);
        active.setBody(new AstBody(new AndNode(List.of(), null, null)));
        active.setDecisionBindings(List.of(new DecisionBinding("BLOCK", 100)));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(active);

        RuleDetailVO vo = configService.getRuleDetail(1L, 10L);

        assertThat(vo.ruleDefinitionId()).isEqualTo(10L);
        assertThat(vo.code()).isEqualTo("rule.a");
        assertThat(vo.sceneCode()).isEqualTo("risk.transfer");
        assertThat(vo.currentVersionId()).isEqualTo(42L);
        assertThat(((AstBody) vo.body()).conditionAst()).isInstanceOf(AndNode.class);
        assertThat(vo.decisionBindings()).hasSize(1);
        assertThat(vo.decisionBindings().get(0).decisionCode()).isEqualTo("BLOCK");
        assertThat(vo.decisionBindings().get(0).priority()).isEqualTo(100);
    }

    @Test
    void getRuleDetail_规则不存在_抛IllegalArgument() {
        when(ruleDefinitionMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> configService.getRuleDetail(1L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规则不存在");
    }

    @Test
    void editDraft_delegatesContentToPublishService() {
        when(publishService.editDraft(any(), any(), any(), any()))
                .thenReturn(new DraftCreatedResult(10L, 20L, 1L, "DRAFT"));

        RuleContent content = new RuleContent("名", "AST_BOOLEAN", null, null, null, null);
        configService.editDraft(1L, 10L, content, "actor");

        // tenantId 字符串 "1" 转 Long，content 原样透传 publishService（kind 解析下沉至 publishService）
        verify(publishService).editDraft(eq(1L), eq(10L), eq(content), eq("actor"));
    }

    @Test
    void newVersion_delegatesContentToPublishService() {
        when(publishService.newVersion(any(), any(), any(), any(), any()))
                .thenReturn(new DraftCreatedResult(10L, 30L, 2L, "DRAFT"));

        RuleContent content = new RuleContent("名", "AST_BOOLEAN", null, null, null, null);
        configService.newVersion(1L, 10L, content, 50L, "actor");

        // content 原样透传 publishService，fromVersionId 原样透传
        verify(publishService).newVersion(eq(1L), eq(10L), eq(content), eq(50L), eq("actor"));
    }

    @Test
    void deleteRule_delegatesWithTenantIdConvertedToLong() {
        configService.deleteRule(1L, 10L, "actor");

        // tenantId 字符串 "1" 转 Long 后透传 publishService
        verify(publishService).deleteRule(1L, 10L, "actor");
    }

    @Test
    void deleteDraftVersion_delegatesWithTenantIdConvertedToLong() {
        configService.deleteDraftVersion(1L, 10L, 100L, "actor");

        // tenantId 字符串 "1" 转 Long 后透传 publishService，versionId 原样透传
        verify(publishService).deleteDraftVersion(1L, 10L, 100L, "actor");
    }

    @Test
    void getRuleVersion_returnsTypedContent() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);
        RuleVersion v = new RuleVersion();
        v.setId(20L);
        v.setRuleDefinitionId(10L);
        v.setVersion(2L);
        v.setStatus(RuleVersionStatus.ACTIVE);
        v.setKind(RuleKind.AST_BOOLEAN);
        v.setBody(new AstBody(new AndNode(List.of(), null, null)));
        v.setTriggerEventTypes(List.of("TXN"));
        when(ruleVersionMapper.selectById(20L)).thenReturn(v);

        RuleVersionContentVO vo = configService.getRuleVersion(1L, 10L, 20L);

        assertThat(vo.ruleVersionId()).isEqualTo(20L);
        assertThat(vo.version()).isEqualTo(2L);
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.kind()).isEqualTo("AST_BOOLEAN");
        assertThat(((AstBody) vo.body()).conditionAst()).isNotNull();
        assertThat(vo.triggerEventTypes()).containsExactly("TXN");
    }

    @Test
    void getRuleVersion_rejectsVersionNotBelongingToRule() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);
        RuleVersion v = new RuleVersion();
        v.setId(20L);
        v.setRuleDefinitionId(99L); // 不属于 rule 10
        when(ruleVersionMapper.selectById(20L)).thenReturn(v);

        assertThatThrownBy(() -> configService.getRuleVersion(1L, 10L, 20L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getRuleVersion_rejectsCrossTenant() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(2L); // 实际属租户 2
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);

        assertThatThrownBy(() -> configService.getRuleVersion(1L, 10L, 20L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
