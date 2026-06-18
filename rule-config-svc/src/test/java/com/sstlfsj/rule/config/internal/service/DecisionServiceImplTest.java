package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sstlfsj.rule.config.api.service.DecisionService.RuleRef;
import com.sstlfsj.rule.config.api.service.UsageCount;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.DecisionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DecisionServiceImpl 单元测试：tenant 级 CRUD + 审计事件 + 反向血缘/get/批量计数（mock mapper，不依赖 Spring 容器）。 */
@ExtendWith(MockitoExtension.class)
class DecisionServiceImplTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, DecisionDefinition.class);
        TableInfoHelper.initTableInfo(assistant, RuleDefinition.class);
        TableInfoHelper.initTableInfo(assistant, RuleVersion.class);
        TableInfoHelper.initTableInfo(assistant, SceneDef.class);
    }

    @Mock DecisionDefinitionMapper mapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock SceneMapper sceneMapper;
    @InjectMocks DecisionServiceImpl sut;

    private static final Long TENANT = 1L;

    @Test
    void create_persistsActiveAndAudits() {
        sut.create(9001L, "REJECT", "拒绝", 10, "高风险拒绝", "actor");

        ArgumentCaptor<DecisionDefinition> cap = ArgumentCaptor.forClass(DecisionDefinition.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getCode()).isEqualTo("REJECT");
        assertThat(cap.getValue().getStatus()).isEqualTo(DecisionStatus.ACTIVE);
        verify(eventPublisher).publishEvent(any(OperationAuditedEvent.class));
    }

    @Test
    void create_rejectsDuplicateCode() {
        when(mapper.findByCode(9001L, "REJECT")).thenReturn(new DecisionDefinition());
        assertThatThrownBy(() -> sut.create(9001L, "REJECT", "拒绝", 10, null, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void disable_setsStatusDisabled() {
        DecisionDefinition existing = new DecisionDefinition();
        existing.setId(7L); existing.setTenantId(9001L); existing.setCode("REJECT");
        existing.setStatus(DecisionStatus.ACTIVE);
        when(mapper.findByCode(9001L, "REJECT")).thenReturn(existing);

        sut.disable(9001L, "REJECT", "actor");

        ArgumentCaptor<DecisionDefinition> cap = ArgumentCaptor.forClass(DecisionDefinition.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(DecisionStatus.DISABLED);
    }

    @Test
    void update_rejectsWhenNotFound() {
        when(mapper.findByCode(9001L, "X")).thenReturn(null);
        assertThatThrownBy(() -> sut.update(9001L, "X", "n", 1, null, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void findRulesProducingDecision_returnsOnlyProducingRules() {
        RuleDefinition rd1 = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "PUBLISHED");
        RuleDefinition rd2 = ruleDefinition(102L, "risk.login", "登录风控", 11L, "DISABLED");
        RuleVersion rv1 = ruleVersion(1001L, 101L, List.of(new DecisionBinding("REJECT", 10)));
        RuleVersion rv2 = ruleVersion(1002L, 101L, List.of(new DecisionBinding("PASS", 5)));
        RuleVersion rv3 = ruleVersion(1003L, 102L, List.of(new DecisionBinding("REJECT", 8)));
        RuleVersion rv4 = ruleVersion(1004L, 102L, List.of());

        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of(rd1, rd2));
        when(ruleVersionMapper.findActiveWithDecisionByRuleDefIds(any())).thenReturn(List.of(rv1, rv2, rv3, rv4));
        when(sceneMapper.findByIds(any())).thenReturn(List.of(scene(10L, "risk.transfer"), scene(11L, "risk.login")));

        List<RuleRef> result = sut.findRulesProducingDecision(TENANT, "REJECT");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(RuleRef::ruleDefinitionId).containsExactlyInAnyOrder(101L, 102L);
        assertThat(result).anySatisfy(r -> {
            assertThat(r.ruleCode()).isEqualTo("risk.transfer");
            assertThat(r.sceneCode()).isEqualTo("risk.transfer");
            assertThat(r.status()).isEqualTo("PUBLISHED");
        });
        assertThat(result).anySatisfy(r -> assertThat(r.status()).isEqualTo("DISABLED"));
    }

    @Test
    void findRulesProducingDecision_noRules_returnsEmpty() {
        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of());
        assertThat(sut.findRulesProducingDecision(TENANT, "REJECT")).isEmpty();
        verifyNoInteractions(ruleVersionMapper);
    }

    @Test
    void countRuleUsages_aggregatesPerDecisionCode_dedupPerRule() {
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账", 10L, "PUBLISHED");
        RuleVersion rv1 = ruleVersion(1001L, 101L, List.of(new DecisionBinding("REJECT", 10)));
        RuleVersion rv2 = ruleVersion(1002L, 101L, List.of(new DecisionBinding("REJECT", 9), new DecisionBinding("REVIEW", 3)));
        RuleVersion rv3 = ruleVersion(1003L, 101L, List.of(new DecisionBinding("REJECT", 8), new DecisionBinding("REJECT", 7)));

        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of(rd));
        when(ruleVersionMapper.findActiveWithDecisionByRuleDefIds(any())).thenReturn(List.of(rv1, rv2, rv3));

        List<UsageCount> counts = sut.countRuleUsages(TENANT);

        assertThat(counts).anySatisfy(c -> { assertThat(c.code()).isEqualTo("REJECT"); assertThat(c.count()).isEqualTo(3); });
        assertThat(counts).anySatisfy(c -> { assertThat(c.code()).isEqualTo("REVIEW"); assertThat(c.count()).isEqualTo(1); });
    }

    @Test
    void countRuleUsages_skipsNullDecisionCode() {
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账", 10L, "PUBLISHED");
        // decisionCode 为 null 的绑定（异常数据）须被过滤，不得触发 merge NPE
        RuleVersion rv = ruleVersion(1001L, 101L, List.of(
                new DecisionBinding(null, 1), new DecisionBinding("REJECT", 2)));

        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of(rd));
        when(ruleVersionMapper.findActiveWithDecisionByRuleDefIds(any())).thenReturn(List.of(rv));

        List<UsageCount> counts = sut.countRuleUsages(TENANT);

        assertThat(counts).extracting(UsageCount::code).containsExactly("REJECT");
    }

    @Test
    void get_existing_returnsIt_missing_throws() {
        DecisionDefinition d = new DecisionDefinition();
        d.setCode("REJECT");
        when(mapper.findByCode(TENANT, "REJECT")).thenReturn(d);
        when(mapper.findByCode(TENANT, "NOPE")).thenReturn(null);

        assertThat(sut.get(TENANT, "REJECT").getCode()).isEqualTo("REJECT");
        assertThatThrownBy(() -> sut.get(TENANT, "NOPE")).isInstanceOf(IllegalArgumentException.class);
    }

    private RuleDefinition ruleDefinition(Long id, String code, String name, Long sceneId, String status) {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(id); rd.setTenantId(TENANT); rd.setCode(code); rd.setName(name);
        rd.setSceneId(sceneId); rd.setStatus(RuleDefinitionStatus.valueOf(status));
        return rd;
    }
    private SceneDef scene(Long id, String code) { SceneDef s = new SceneDef(); s.setId(id); s.setCode(code); return s; }
    private RuleVersion ruleVersion(Long id, Long ruleDefinitionId, List<DecisionBinding> bindings) {
        RuleVersion rv = new RuleVersion();
        rv.setId(id); rv.setRuleDefinitionId(ruleDefinitionId);
        rv.setStatus(RuleVersionStatus.ACTIVE); rv.setDecisionBindings(bindings);
        return rv;
    }
}
