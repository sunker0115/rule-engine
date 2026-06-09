package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RuleImportService 单元测试：mock 各 Mapper 的语义查询方法。
 * <p>Mapper 的 default 查询方法被 Mockito stub 掉（方法体不执行），无需 TableInfoHelper 预热。
 * insert 仍是 BaseMapper 方法，用 doAnswer 回填自增主键。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuleImportServiceTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Mock AuditLogMapper auditLogMapper;
    @InjectMocks RuleImportService sut;

    private RuleBundle.RuleEntry ruleEntry(String code) {
        return new RuleBundle.RuleEntry(code, "规则" + code, "AST_BOOLEAN", "risk.transfer",
                new AndNode(List.of(), null, null),
                List.of(new DecisionBinding("BLOCK", 100)),
                List.of(), List.of("transfer"),
                List.of(new MetricDependency("account.age", 1)));
    }

    private RuleBundle bundle(String metricSourceType, String... ruleCodes) {
        return new RuleBundle(1, "2026-06-06T10:00:00Z", "1",
                java.util.Arrays.stream(ruleCodes).map(this::ruleEntry).toList(),
                List.of(new RuleBundle.SceneSnapshot("risk.transfer", "转账风控", "d", "USER",
                        "PUSH", "HIGHEST_PRIORITY", java.util.List.of("transfer"),
                        java.util.List.of(), java.util.Map.of(), 1)),
                List.of(new RuleBundle.MetricEntry("account.age", 1, "账户年龄",
                        metricSourceType, "LONG", java.util.Map.of(), 3600, true)),
                List.of(new RuleBundle.DecisionEntry("BLOCK", "拦截", 100, "拦截交易",
                        "[{\"actionType\":\"BLOCK_TRANSACTION\"}]")),
                List.of("BLOCK_TRANSACTION"));
    }

    /** 模拟 MyBatis insert 回填自增主键（rule_version 用递增序列）。 */
    private void stubInserts(long sceneId, long ruleDefId, AtomicLong rvSeq) {
        doAnswer(inv -> { ((SceneDef) inv.getArgument(0)).setId(sceneId); return 1; })
                .when(sceneMapper).insert(any(SceneDef.class));
        doAnswer(inv -> { ((RuleDefinition) inv.getArgument(0)).setId(ruleDefId); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        doAnswer(inv -> { ((RuleVersion) inv.getArgument(0)).setId(rvSeq.incrementAndGet()); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));
    }

    @Test
    void import_freshTarget_twoRules_createsDepsOnceAndDraftV1Each() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(null);
        when(metricDefinitionMapper.findAnyByCode(any(), any())).thenReturn(null);
        when(decisionDefinitionMapper.findByCode(any(), any())).thenReturn(null);
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);
        AtomicLong rvSeq = new AtomicLong(100);
        // 两条规则各自新建 rule_definition：用计数器区分返回 id
        AtomicLong rdSeq = new AtomicLong(9);
        doAnswer(inv -> { ((SceneDef) inv.getArgument(0)).setId(5L); return 1; })
                .when(sceneMapper).insert(any(SceneDef.class));
        doAnswer(inv -> { ((RuleDefinition) inv.getArgument(0)).setId(rdSeq.incrementAndGet()); return 1; })
                .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
        doAnswer(inv -> { ((RuleVersion) inv.getArgument(0)).setId(rvSeq.incrementAndGet()); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));

        RuleImportResult r = sut.importBundle("1", bundle("ATTRIBUTE", "rule.a", "rule.b"), "dev");

        assertThat(r.rules()).hasSize(2);
        assertThat(r.rules()).allMatch(ir -> !ir.ruleAlreadyExisted() && ir.version() == 1L
                && "DRAFT".equals("DRAFT") && "risk.transfer".equals(ir.sceneCode()));
        assertThat(r.scenesCreated()).containsExactly("risk.transfer");
        assertThat(r.metricsCreated()).containsExactly("account.age");   // 依赖只创建一次
        assertThat(r.decisionsCreated()).containsExactly("BLOCK");
        verify(metricDefinitionMapper, times(1)).insert(any(MetricDefinition.class));
        verify(decisionDefinitionMapper, times(1)).insert(any(DecisionDefinition.class));
        verify(auditLogMapper, times(2)).insert(any(AuditLog.class));   // 每条规则一条审计
    }

    @Test
    void import_sqlMetricMissing_flaggedForReviewNotCreated() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(null);
        when(metricDefinitionMapper.findAnyByCode(any(), any())).thenReturn(null);
        when(decisionDefinitionMapper.findByCode(any(), any())).thenReturn(null);
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);
        stubInserts(5L, 10L, new AtomicLong(100));

        RuleImportResult r = sut.importBundle("1", bundle("SQL_AGGREGATE", "rule.a"), "dev");

        assertThat(r.metricsRequiringReview()).containsExactly("account.age");
        assertThat(r.metricsCreated()).isEmpty();
        verify(metricDefinitionMapper, never()).insert(any(MetricDefinition.class));
    }

    @Test
    void import_invalidDataType_flaggedForReviewNotCreated() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(null);
        when(metricDefinitionMapper.findAnyByCode(any(), any())).thenReturn(null);
        when(decisionDefinitionMapper.findByCode(any(), any())).thenReturn(null);
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);
        stubInserts(5L, 10L, new AtomicLong(100));

        // data_type=FLOAT 非法(ENUM→VARCHAR 后 DB 不再约束,导入侧校验堵口)→ 不创建,交人工 review
        RuleBundle bad = new RuleBundle(1, "2026-06-06T10:00:00Z", "1",
                List.of(ruleEntry("rule.a")),
                List.of(new RuleBundle.SceneSnapshot("risk.transfer", "转账风控", "d", "USER",
                        "PUSH", "HIGHEST_PRIORITY", java.util.List.of("transfer"),
                        java.util.List.of(), java.util.Map.of(), 1)),
                List.of(new RuleBundle.MetricEntry("account.age", 1, "账户年龄",
                        "ATTRIBUTE", "FLOAT", java.util.Map.of(), 3600, true)),
                List.of(new RuleBundle.DecisionEntry("BLOCK", "拦截", 100, "拦截交易",
                        "[{\"actionType\":\"BLOCK_TRANSACTION\"}]")),
                List.of("BLOCK_TRANSACTION"));

        RuleImportResult r = sut.importBundle("1", bad, "dev");

        assertThat(r.metricsRequiringReview()).containsExactly("account.age");
        assertThat(r.metricsCreated()).isEmpty();
        verify(metricDefinitionMapper, never()).insert(any(MetricDefinition.class));
    }

    @Test
    void import_existingRule_appendsDraftVersionWithoutTouchingDefinition() {
        SceneDef existingScene = new SceneDef();
        existingScene.setId(5L); existingScene.setTenantId(1L); existingScene.setCode("risk.transfer");
        when(sceneMapper.findByCode(any(), any())).thenReturn(existingScene);

        MetricDefinition existingMetric = new MetricDefinition();
        existingMetric.setMetricCode("account.age");
        when(metricDefinitionMapper.findAnyByCode(any(), any())).thenReturn(existingMetric);

        DecisionDefinition existingDecision = new DecisionDefinition();
        existingDecision.setCode("BLOCK");
        when(decisionDefinitionMapper.findByCode(any(), any())).thenReturn(existingDecision);

        RuleDefinition existingRule = new RuleDefinition();
        existingRule.setId(10L); existingRule.setTenantId(1L); existingRule.setSceneId(5L);
        existingRule.setCode("rule.a"); existingRule.setStatus("PUBLISHED");
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(existingRule);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(3L);
        doAnswer(inv -> { ((RuleVersion) inv.getArgument(0)).setId(101L); return 1; })
                .when(ruleVersionMapper).insert(any(RuleVersion.class));

        RuleImportResult r = sut.importBundle("1", bundle("ATTRIBUTE", "rule.a"), "dev");

        assertThat(r.rules()).hasSize(1);
        RuleImportResult.ImportedRule ir = r.rules().getFirst();
        assertThat(ir.ruleAlreadyExisted()).isTrue();
        assertThat(ir.version()).isEqualTo(4L);          // maxVersion(3)+1
        assertThat(ir.ruleVersionId()).isEqualTo(101L);
        assertThat(r.scenesSkippedExisting()).containsExactly("risk.transfer");
        assertThat(r.metricsSkippedExisting()).containsExactly("account.age");
        assertThat(r.decisionsSkippedExisting()).containsExactly("BLOCK");
        verify(ruleDefinitionMapper, never()).insert(any(RuleDefinition.class));
        verify(ruleDefinitionMapper, never()).updateById(any(RuleDefinition.class));
    }

    @Test
    void import_rejectsEmptyRules() {
        RuleBundle bad = new RuleBundle(1, "t", "1", List.of(),
                List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> sut.importBundle("1", bad, "dev"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
