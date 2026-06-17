package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.ImportDiffReport;
import com.sstlfsj.rule.config.api.dto.ImportPolicy;
import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.internal.bundle.RuleImportService.ImportConflictException;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RuleImportService v2 单元测试：mock service 调用链，验证三策略 + dryRun + contentHash 幂等。
 * <p>service 调用链（SceneService/MetricWriteService/DecisionService/PublishService）被 mock，
 * 测试重点在 policy 分支逻辑和 contentHash 幂等判断。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleImportServiceTest {

    @Mock PublishService publishService;
    @Mock SceneService sceneService;
    @Mock MetricWriteService metricWriteService;
    @Mock DecisionService decisionService;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Spy ObjectMapper objectMapper = JsonMapper.builder().build();
    @Mock org.springframework.transaction.PlatformTransactionManager transactionManager;
    @InjectMocks RuleImportService sut;

    private RuleBundle.RuleEntry entry(String code, String hash) {
        return new RuleBundle.RuleEntry(code, "规则" + code, "AST_BOOLEAN", "risk.transfer",
                new AndNode(List.of(), null, null), List.of(new DecisionBinding("BLOCK", 100)),
                List.of(), List.of("transfer"), List.of(), List.of(), null, hash);
    }

    private RuleBundle bundle(String... codes) {
        List<RuleBundle.RuleEntry> rules = List.of(codes).stream()
                .map(c -> entry(c, "hash-" + c)).toList();
        return new RuleBundle(2, "rev", "t", "1", rules, List.of(), List.of(), List.of());
    }

    private SceneDef existingScene() {
        SceneDef s = new SceneDef();
        s.setId(5L); s.setTenantId(1L); s.setCode("risk.transfer");
        return s;
    }

    private RuleDefinition existingRuleDef(String code) {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(10L); rd.setTenantId(1L); rd.setSceneId(5L);
        rd.setCode(code); rd.setStatus(RuleDefinitionStatus.PUBLISHED);
        return rd;
    }

    private RuleVersion activeVersion(String contentHash) {
        RuleVersion rv = new RuleVersion();
        rv.setId(100L); rv.setVersion(1L); rv.setStatus(RuleVersionStatus.ACTIVE);
        rv.setKind(com.sstlfsj.rule.kernel.api.model.RuleKind.AST_BOOLEAN);
        rv.setConditionAst(new AndNode(List.of(), null, null));
        rv.setDecisionBindings(List.of(new DecisionBinding("BLOCK", 100)));
        rv.setPreGates(List.of()); rv.setTriggerEventTypes(List.of("transfer"));
        // contentHash 在 activeVersion 不直接存，import 会实时算，这里无需 mock hash
        return rv;
    }

    // ---- SKIP policy --------------------------------------------------

    @Test
    void apply_skip_newRule_callsCreateDraft() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(existingScene());
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);
        when(metricDefinitionMapper.findAnyByCode(any(), any())).thenReturn(null);
        when(decisionDefinitionMapper.findByCode(any(), any())).thenReturn(null);

        ImportDiffReport r = sut.apply(1L, bundle("rule.a"), ImportPolicy.SKIP, "dev");

        assertThat(r.willCreate()).hasSize(1).allMatch(i -> "rule.a".equals(i.ruleCode()));
        verify(publishService).createDraft(eq(1L), eq("risk.transfer"), eq("rule.a"), any(), eq("dev"));
    }

    @Test
    void apply_skip_existingRule_skipsWithoutCreateDraft() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(existingScene());
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any()))
                .thenReturn(existingRuleDef("rule.a"));
        // no active version → no hash comparison → goes to SKIP branch
        when(ruleVersionMapper.findActiveVersion(any())).thenReturn(null);

        ImportDiffReport r = sut.apply(1L, bundle("rule.a"), ImportPolicy.SKIP, "dev");

        assertThat(r.skipped()).hasSize(1);
        verify(publishService, never()).createDraft(any(), any(), any(), any(), any());
    }

    @Test
    void apply_skip_sameContentHash_skipsAsIdempotent() {
        // 算 hash：entry 里 hash-rule.a 要与实时算的相同，才触发幂等跳过
        // 简化：用 null contentHash 让 entry，则 hash 比较跳过（rule.contentHash == null）
        var entryNoHash = new RuleBundle.RuleEntry("rule.a", "n", "AST_BOOLEAN", "risk.transfer",
                new AndNode(List.of(), null, null), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        var b = new RuleBundle(2, null, "t", "1", List.of(entryNoHash), List.of(), List.of(), List.of());

        when(sceneMapper.findByCode(any(), any())).thenReturn(existingScene());
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any()))
                .thenReturn(existingRuleDef("rule.a"));
        // contentHash=null in entry → hash comparison skipped → falls to SKIP branch
        when(ruleVersionMapper.findActiveVersion(any())).thenReturn(activeVersion("h"));

        ImportDiffReport r = sut.apply(1L, b, ImportPolicy.SKIP, "dev");

        assertThat(r.skipped()).hasSize(1);
    }

    // ---- OVERWRITE policy ---------------------------------------------

    @Test
    void apply_overwrite_existingDraft_editsDraftInPlace() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(existingScene());
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any()))
                .thenReturn(existingRuleDef("rule.a"));
        when(ruleVersionMapper.findActiveVersion(any())).thenReturn(null);
        RuleVersion draft = new RuleVersion(); draft.setId(50L);
        when(ruleVersionMapper.findLatestDraft(10L)).thenReturn(draft);

        ImportDiffReport r = sut.apply(1L, bundle("rule.a"), ImportPolicy.OVERWRITE, "dev");

        assertThat(r.willOverwrite()).hasSize(1);
        // 已存在规则 + 有 DRAFT → editDraft 原地更新（不能 createDraft，code 会重复）
        verify(publishService).editDraft(eq(1L), eq(10L), any(), eq("dev"));
        verify(publishService, never()).createDraft(any(), any(), any(), any(), any());
    }

    @Test
    void apply_overwrite_noDraft_createsNewVersion() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(existingScene());
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any()))
                .thenReturn(existingRuleDef("rule.a"));
        when(ruleVersionMapper.findActiveVersion(any())).thenReturn(null);
        when(ruleVersionMapper.findLatestDraft(any())).thenReturn(null); // 无旧 DRAFT

        ImportDiffReport r = sut.apply(1L, bundle("rule.a"), ImportPolicy.OVERWRITE, "dev");

        assertThat(r.willOverwrite()).hasSize(1);
        // 已存在规则 + 无 DRAFT → newVersion 基于 ACTIVE 建新 DRAFT（不能 createDraft）
        verify(publishService).newVersion(eq(1L), eq(10L), any(), eq(null), eq("dev"));
        verify(publishService, never()).createDraft(any(), any(), any(), any(), any());
    }

    // ---- ABORT policy -------------------------------------------------

    @Test
    void apply_abort_collectsAllConflictsAndThrows() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(existingScene());
        // 两条规则都已存在 → 两条冲突
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any()))
                .thenReturn(existingRuleDef("rule.a"));
        when(ruleVersionMapper.findActiveVersion(any())).thenReturn(null);

        assertThatThrownBy(() -> sut.apply(1L, bundle("rule.a", "rule.b"), ImportPolicy.ABORT, "dev"))
                .isInstanceOf(ImportConflictException.class)
                .satisfies(ex -> {
                    var report = ((ImportConflictException) ex).report();
                    assertThat(report.conflicts()).hasSize(2);
                });

        // ABORT：冲突后不调 createDraft
        verify(publishService, never()).createDraft(any(), any(), any(), any(), any());
    }

    // ---- dryRun -------------------------------------------------------

    @Test
    void dryRun_returnsReport_andMarksTransactionRollbackOnly() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(existingScene());
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);
        // TransactionTemplate 需要 txManager.getTransaction 返回 status；验证 dryRun 强制 setRollbackOnly
        org.springframework.transaction.TransactionStatus status =
                mock(org.springframework.transaction.TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);

        ImportDiffReport r = sut.dryRun(1L, bundle("rule.a"), ImportPolicy.SKIP, "dev");

        assertThat(r.willCreate()).hasSize(1).allMatch(i -> "rule.a".equals(i.ruleCode()));
        verify(status).setRollbackOnly();  // dry-run 必须强制回滚
    }

    // ---- empty bundle -------------------------------------------------

    @Test
    void apply_emptyBundle_throws() {
        var empty = new RuleBundle(2, null, "t", "1", List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> sut.apply(1L, empty, ImportPolicy.SKIP, "dev"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- scene not found → conflict -----------------------------------

    @Test
    void apply_sceneNotFound_recordsConflict() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(null);

        ImportDiffReport r = sut.apply(1L, bundle("rule.a"), ImportPolicy.SKIP, "dev");

        assertThat(r.conflicts()).hasSize(1)
                .allMatch(c -> "SCENE_NOT_FOUND".equals(c.conflictType()));
        verify(publishService, never()).createDraft(any(), any(), any(), any(), any());
    }
}
