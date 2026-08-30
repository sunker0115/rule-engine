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
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RuleExportService 单元测试：mock 各 Mapper；ObjectMapper 用真实实例（contentHash 需要序列化）。
 */
@ExtendWith(MockitoExtension.class)
class RuleExportServiceTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Spy ObjectMapper objectMapper = JsonMapper.builder().build();  // 真实 om，contentHash/revision 需要
    @InjectMocks RuleExportService sut;

    private RuleDefinition rule(long id, String code) {
        RuleDefinition r = new RuleDefinition();
        r.setId(id); r.setTenantId(1L); r.setSceneCode("risk.transfer");
        r.setCode(code); r.setName("规则" + code); r.setKind(com.sstlfsj.rule.kernel.api.model.RuleKind.AST_BOOLEAN);
        r.setStatus(RuleDefinitionStatus.PUBLISHED);
        return r;
    }

    private RuleVersion activeVersion(long rdId) {
        RuleVersion v = new RuleVersion();
        v.setId(100L + rdId); v.setRuleDefinitionId(rdId); v.setVersion(3L); v.setStatus(RuleVersionStatus.ACTIVE);
        v.setKind(com.sstlfsj.rule.kernel.api.model.RuleKind.AST_BOOLEAN);
        v.setBody(new AstBody(new AndNode(List.of(), null, null)));
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
        s.setEventTypes(List.of("transfer")); s.setPayloadSchema(List.of());
        s.setDefaultParams(java.util.Map.of());
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
    void export_byRuleIds_assemblesV2BundleWithDedupedDeps() {
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a"), rule(11L, "b")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(activeVersion(10L));
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(activeVersion(11L));
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), eq("account.age"), eq(1))).thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b = sut.export(1L, List.of(10L, 11L), null);

        assertThat(b.formatVersion()).isEqualTo(2);
        assertThat(b.revision()).isNotBlank();        // v2：整 bundle SHA-256
        assertThat(b.rules()).hasSize(2);
        assertThat(b.rules()).extracting(RuleBundle.RuleEntry::code).containsExactlyInAnyOrder("a", "b");
        assertThat(b.rules().getFirst().sceneCode()).isEqualTo("risk.transfer");
        assertThat(b.rules().getFirst().contentHash()).isNotBlank();  // v2：规则内容 SHA-256
        assertThat(b.rules().getFirst().body()).isInstanceOf(AstBody.class);  // AST_BOOLEAN：body 为 AstBody
        assertThat(b.rules().getFirst().payloadDependencies())
                .containsExactly(new PayloadDependency("amount", "NUMBER", true));
        assertThat(b.scenes()).hasSize(1);
        assertThat(b.metricDefinitions()).hasSize(1);
        assertThat(b.decisionDefinitions()).hasSize(1);
    }

    @Test
    void export_scriptRule_carriessScriptInEntry() {
        // EXPRESSION_SCRIPT 规则：script 字段随 bundle 携带不丢失
        RuleDefinition rd = rule(12L, "cel-rule");
        rd.setKind(com.sstlfsj.rule.kernel.api.model.RuleKind.EXPRESSION_SCRIPT);
        RuleVersion rv = activeVersion(12L);
        rv.setKind(com.sstlfsj.rule.kernel.api.model.RuleKind.EXPRESSION_SCRIPT);
        rv.setBody(new ScriptBody(new ScriptSource("metrics.amount > 1000", "CEL")));
        when(ruleDefinitionMapper.selectForExport(any(), any(), any())).thenReturn(List.of(rd));
        when(ruleVersionMapper.findActiveVersion(12L)).thenReturn(rv);
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b = sut.export(1L, List.of(12L), null);

        RuleBundle.RuleEntry entry = b.rules().getFirst();
        assertThat(entry.body()).isInstanceOf(ScriptBody.class);
        ScriptBody sb = (ScriptBody) entry.body();
        assertThat(sb.script().source()).isEqualTo("metrics.amount > 1000");
        assertThat(sb.script().lang()).isEqualTo("CEL");
        assertThat(entry.contentHash()).isNotBlank();
    }

    @Test
    void export_twoExports_sameContent_sameRevision() {
        // 相同内容 export 两次 → revision 一致（幂等）
        when(ruleDefinitionMapper.selectForExport(any(), any(), any())).thenReturn(List.of(rule(10L, "a")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(activeVersion(10L));
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b1 = sut.export(1L, List.of(10L), null);
        RuleBundle b2 = sut.export(1L, List.of(10L), null);

        assertThat(b1.revision()).isEqualTo(b2.revision());
        assertThat(b1.rules().getFirst().contentHash()).isEqualTo(b2.rules().getFirst().contentHash());
    }

    @Test
    void export_skipsRulesWithoutActiveVersion() {
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a"), rule(11L, "b")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(activeVersion(10L));
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(null);
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b = sut.export(1L, List.of(10L, 11L), null);

        assertThat(b.rules()).hasSize(1);
        assertThat(b.rules().getFirst().code()).isEqualTo("a");
    }

    @Test
    void export_rejectsWhenNoExportableRule() {
        when(ruleDefinitionMapper.selectForExport(any(), any(), any())).thenReturn(List.of(rule(10L, "a")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(null);

        assertThatThrownBy(() -> sut.export(1L, List.of(10L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("可导出");
    }
}
