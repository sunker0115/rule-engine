package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RuleExportService 单元测试：mock 各 Mapper 的语义查询方法。
 * <p>Mapper 的 default 查询方法会被 Mockito stub 掉（方法体不执行），故无需 TableInfoHelper 预热——
 * wrapper 拼装逻辑由 Mapper default 方法承载，交 Testcontainers 集成测试覆盖真库。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuleExportServiceTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @InjectMocks RuleExportService sut;

    private RuleDefinition rule(long id, String code) {
        RuleDefinition r = new RuleDefinition();
        r.setId(id); r.setTenantId(1L); r.setSceneId(5L);
        r.setCode(code); r.setName("规则" + code); r.setKind(com.sstlfsj.rule.kernel.api.model.RuleKind.AST_BOOLEAN);
        r.setStatus(RuleDefinitionStatus.PUBLISHED);
        return r;
    }

    private RuleVersion activeVersion(long rdId) {
        RuleVersion v = new RuleVersion();
        v.setId(100L + rdId); v.setRuleDefinitionId(rdId); v.setVersion(3L); v.setStatus(RuleVersionStatus.ACTIVE);
        v.setKind(com.sstlfsj.rule.kernel.api.model.RuleKind.AST_BOOLEAN);
        v.setConditionAst(new AndNode(List.of(), null, null));
        v.setDecisionBindings(List.of(new DecisionBinding("BLOCK", 100)));
        v.setPreGates(List.of());
        v.setTriggerEventTypes(List.of("transfer"));
        v.setMetricDependencies(List.of(new MetricDependency("account.age", 1)));
        v.setPayloadDependencies(List.of(new PayloadDependency("amount", "NUMBER", true)));
        return v;
    }

    private SceneDef scene() {
        SceneDef s = new SceneDef();
        s.setId(5L); s.setTenantId(1L); s.setCode("risk.transfer"); s.setName("转账风控");
        s.setSubjectType(com.sstlfsj.rule.kernel.api.model.SubjectType.USER);
        s.setDominantMode(com.sstlfsj.rule.config.internal.domain.DominantMode.PUSH);
        s.setDecisionStrategy(com.sstlfsj.rule.config.internal.domain.DecisionStrategy.HIGHEST_PRIORITY);
        s.setEventTypes(java.util.List.of("transfer")); s.setPayloadSchema(java.util.List.of());
        s.setDefaultParams(java.util.Map.of());
        s.setPayloadSchemaVersion(1);
        return s;
    }

    private MetricDefinition metric() {
        MetricDefinition m = new MetricDefinition();
        m.setMetricCode("account.age"); m.setVersion(1); m.setName("账户年龄");
        m.setSourceType("ATTRIBUTE"); m.setDataType("LONG"); m.setParams(java.util.Map.of());
        m.setCacheTtlSeconds(3600); m.setAllowProvided(true);
        return m;
    }

    private DecisionDefinition decision() {
        DecisionDefinition d = new DecisionDefinition();
        d.setCode("BLOCK"); d.setName("拦截"); d.setPriority(100); d.setDescription("拦截交易");
        return d;
    }

    @Test
    void export_byRuleIds_assemblesMultiRuleBundleWithDedupedDeps() {
        // 两条规则共享同一 scene / metric / decision，依赖应去重
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a"), rule(11L, "b")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(activeVersion(10L));
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(activeVersion(11L));
        when(sceneMapper.findByIds(any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), eq("account.age"), eq(1)))
                .thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b = sut.export("1", List.of(10L, 11L), null);

        assertThat(b.bundleVersion()).isEqualTo(1);
        assertThat(b.rules()).hasSize(2);
        assertThat(b.rules()).extracting(RuleBundle.RuleEntry::code).containsExactlyInAnyOrder("a", "b");
        assertThat(b.rules().getFirst().sceneCode()).isEqualTo("risk.transfer");
        assertThat(b.rules().getFirst().payloadDependencies())
                .containsExactly(new PayloadDependency("amount", "NUMBER", true));
        assertThat(b.scenes()).hasSize(1);                       // 去重
        assertThat(b.metricDefinitions()).hasSize(1);            // 去重
        assertThat(b.decisionDefinitions()).hasSize(1);          // 去重
    }

    @Test
    void export_skipsRulesWithoutActiveVersion() {
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a"), rule(11L, "b")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(activeVersion(10L));
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(null);   // 第二条无 ACTIVE
        when(sceneMapper.findByIds(any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b = sut.export("1", List.of(10L, 11L), null);

        assertThat(b.rules()).hasSize(1);
        assertThat(b.rules().getFirst().code()).isEqualTo("a");
    }

    @Test
    void export_rejectsWhenNoExportableRule() {
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(null);   // 无 ACTIVE

        assertThatThrownBy(() -> sut.export("1", List.of(10L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("可导出");
    }

    @Test
    void export_bySceneId_filtersBySceneId() {
        // 入参直接是 sceneId，service 透传给 mapper.selectForExport
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(activeVersion(10L));
        when(sceneMapper.findByIds(any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b = sut.export("1", null, 5L);

        assertThat(b.rules()).hasSize(1);
        assertThat(b.rules().getFirst().sceneCode()).isEqualTo("risk.transfer");   // Bundle 内仍是 code
    }
}
