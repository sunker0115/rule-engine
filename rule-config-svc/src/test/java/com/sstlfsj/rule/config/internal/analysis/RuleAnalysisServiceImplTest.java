package com.sstlfsj.rule.config.internal.analysis;

import com.sstlfsj.rule.config.internal.domain.DecisionStrategy;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.analysis.RuleSetAnalysisReport;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * RuleAnalysisServiceImpl 单元测试:mock 各 Mapper,验证「读快照→拆 AnalyzableRule→调 kernel 分析器」编排接线,
 * 不重测分析器内部判定。
 */
@ExtendWith(MockitoExtension.class)
class RuleAnalysisServiceImplTest {

    @Mock SceneMapper sceneMapper;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @InjectMocks RuleAnalysisServiceImpl sut;

    private static ConditionNode cond(String type, String metric, Object threshold) {
        return new ConditionNode(type, metric, null,
                Map.of(ConditionParams.THRESHOLD, threshold), 0.0, null, ValueRef.METRIC);
    }

    private SceneDef scene(long id, DecisionStrategy strategy) {
        SceneDef s = new SceneDef();
        s.setId(id);
        s.setTenantId(1L);
        s.setCode("scene-1");
        s.setDecisionStrategy(strategy);
        return s;
    }

    private RuleDefinition ruleDef(long id, String code) {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(id);
        rd.setTenantId(1L);
        rd.setSceneId(5L);
        rd.setCode(code);
        rd.setKind(RuleKind.AST_BOOLEAN);
        return rd;
    }

    private RuleVersion activeVersion(long rdId, AstNode ast) {
        RuleVersion v = new RuleVersion();
        v.setId(100L + rdId);
        v.setRuleDefinitionId(rdId);
        v.setVersion(1L);
        v.setStatus(RuleVersionStatus.ACTIVE);
        v.setKind(RuleKind.AST_BOOLEAN);
        v.setConditionAst(ast);
        v.setDecisionBindings(List.of(new DecisionBinding("D_PASS", 1)));
        return v;
    }

    private RuleVersion draftVersion(long rdId, AstNode ast) {
        RuleVersion v = new RuleVersion();
        v.setId(200L + rdId);
        v.setRuleDefinitionId(rdId);
        v.setVersion(2L);
        v.setStatus(RuleVersionStatus.DRAFT);
        v.setKind(RuleKind.AST_BOOLEAN);
        v.setConditionAst(ast);
        v.setDecisionBindings(List.of(new DecisionBinding("D_PASS", 1)));
        return v;
    }

    @Test
    void scene_with_two_active_rules_overlap_is_reported() {
        // R_a age>10 与 R_b age>20 区间相交、同决策 → 应得到一条 overlap,验证拆 AnalyzableRule + 调分析器接线正确
        when(sceneMapper.findByCode(1L, "scene-1")).thenReturn(scene(5L, DecisionStrategy.HIGHEST_PRIORITY));
        RuleDefinition rdA = ruleDef(11L, "R_a");
        RuleDefinition rdB = ruleDef(12L, "R_b");
        when(ruleDefinitionMapper.findByTenantAndSceneIds(1L, List.of(5L))).thenReturn(List.of(rdA, rdB));
        when(ruleVersionMapper.findLatestDraft(11L)).thenReturn(null);
        when(ruleVersionMapper.findLatestDraft(12L)).thenReturn(null);
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(activeVersion(11L, cond(ConditionTypes.GT, "age", 10)));
        when(ruleVersionMapper.findActiveVersion(12L)).thenReturn(activeVersion(12L, cond(ConditionTypes.GT, "age", 20)));

        RuleSetAnalysisReport report = sut.analyze(1L, "scene-1");

        assertThat(report.sceneCode()).isEqualTo("scene-1");
        assertThat(report.overlaps()).hasSize(1);
        assertThat(report.overlaps().getFirst().locA()).isEqualTo("R_a");
        assertThat(report.unanalyzableRules()).isEmpty();
    }

    @Test
    void scene_not_found_throws_illegal_argument() {
        when(sceneMapper.findByCode(1L, "missing")).thenReturn(null);

        assertThatThrownBy(() -> sut.analyze(1L, "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void scene_without_active_rules_returns_empty_report() {
        when(sceneMapper.findByCode(1L, "scene-1")).thenReturn(scene(5L, DecisionStrategy.HIGHEST_PRIORITY));
        when(ruleDefinitionMapper.findByTenantAndSceneIds(1L, List.of(5L))).thenReturn(List.of());

        RuleSetAnalysisReport report = sut.analyze(1L, "scene-1");

        assertThat(report.sceneCode()).isEqualTo("scene-1");
        assertThat(report.overlaps()).isEmpty();
        assertThat(report.incoherences()).isEmpty();
        assertThat(report.deadRules()).isEmpty();
        assertThat(report.conflicts()).isEmpty();
        assertThat(report.coverageGaps()).isEmpty();
        assertThat(report.unanalyzableRules()).isEmpty();
    }

    /** A(优先级 9) age>10 完全覆盖 B(优先级 1) age∈[20,30]:HIGHEST_PRIORITY/FIRST_HIT 下 B 死,ALL_HITS 下不死。 */
    private ConditionNode between(int min, int max) {
        return new ConditionNode(ConditionTypes.BETWEEN, "age", null,
                Map.of(ConditionParams.MIN, min, ConditionParams.MAX, max), 0.0, null, ValueRef.METRIC);
    }

    private void stubWideCoversNarrow(DecisionStrategy strategy) {
        when(sceneMapper.findByCode(1L, "scene-1")).thenReturn(scene(5L, strategy));
        when(ruleDefinitionMapper.findByTenantAndSceneIds(1L, List.of(5L)))
                .thenReturn(List.of(ruleDef(11L, "R_a"), ruleDef(12L, "R_b")));
        RuleVersion wide = activeVersion(11L, cond(ConditionTypes.GT, "age", 10));
        wide.setDecisionBindings(List.of(new DecisionBinding("D_A", 9)));
        RuleVersion narrow = activeVersion(12L, between(20, 30));
        narrow.setDecisionBindings(List.of(new DecisionBinding("D_B", 1)));
        when(ruleVersionMapper.findLatestDraft(11L)).thenReturn(null);
        when(ruleVersionMapper.findLatestDraft(12L)).thenReturn(null);
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(wide);
        when(ruleVersionMapper.findActiveVersion(12L)).thenReturn(narrow);
    }

    @ParameterizedTest
    @EnumSource(DecisionStrategy.class)
    void decision_strategy_maps_through_and_drives_dead_rule_judgment(DecisionStrategy strategy) {
        // 把 DecisionStrategy→SceneExecutionStrategy 的契约钉在端到端行为上:
        // ALL_HITS 全量收集不掩盖 → 无死规则;HIGHEST_PRIORITY/FIRST_HIT 高优先级覆盖 → B 死。
        // 若映射错(如 ALL_HITS 被错配成 HIGHEST_PRIORITY),本断言会失败。
        stubWideCoversNarrow(strategy);

        RuleSetAnalysisReport report = sut.analyze(1L, "scene-1");

        if (strategy == DecisionStrategy.ALL_HITS) {
            assertThat(report.deadRules()).isEmpty();
        } else {
            assertThat(report.deadRules())
                    .extracting(f -> f.deadRuleCode(), f -> f.coveredByRuleCode())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("R_b", "R_a"));
        }
    }

    @Test
    void null_decision_strategy_falls_back_to_highest_priority() {
        // decisionStrategy 为 null → 回落 HIGHEST_PRIORITY → 与显式 HIGHEST_PRIORITY 同样判 B 死
        stubWideCoversNarrow(null);

        RuleSetAnalysisReport report = sut.analyze(1L, "scene-1");

        assertThat(report.deadRules())
                .extracting(f -> f.deadRuleCode())
                .containsExactly("R_b");
    }

    @Test
    void rule_definition_without_draft_or_active_version_is_skipped() {
        // 规则定义存在但既无 DRAFT 也无 ACTIVE → 跳过,不入分析,返回空报告
        when(sceneMapper.findByCode(1L, "scene-1")).thenReturn(scene(5L, DecisionStrategy.HIGHEST_PRIORITY));
        when(ruleDefinitionMapper.findByTenantAndSceneIds(1L, List.of(5L)))
                .thenReturn(List.of(ruleDef(11L, "R_a")));
        when(ruleVersionMapper.findLatestDraft(11L)).thenReturn(null);
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(null);

        RuleSetAnalysisReport report = sut.analyze(1L, "scene-1");

        assertThat(report.overlaps()).isEmpty();
        assertThat(report.unanalyzableRules()).isEmpty();
    }

    @Test
    void rule_definition_with_only_active_version_is_analyzed() {
        // 仅有 ACTIVE(无 DRAFT) → 回落 ACTIVE,行为不变:两规则区间相交得到一条 overlap
        when(sceneMapper.findByCode(1L, "scene-1")).thenReturn(scene(5L, DecisionStrategy.HIGHEST_PRIORITY));
        when(ruleDefinitionMapper.findByTenantAndSceneIds(1L, List.of(5L)))
                .thenReturn(List.of(ruleDef(11L, "R_a"), ruleDef(12L, "R_b")));
        when(ruleVersionMapper.findLatestDraft(11L)).thenReturn(null);
        when(ruleVersionMapper.findLatestDraft(12L)).thenReturn(null);
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(activeVersion(11L, cond(ConditionTypes.GT, "age", 10)));
        when(ruleVersionMapper.findActiveVersion(12L)).thenReturn(activeVersion(12L, cond(ConditionTypes.GT, "age", 20)));

        RuleSetAnalysisReport report = sut.analyze(1L, "scene-1");

        assertThat(report.overlaps()).hasSize(1);
        assertThat(report.overlaps().getFirst().locA()).isEqualTo("R_a");
    }

    @Test
    void rule_definition_with_only_draft_version_is_analyzed() {
        // 用户场景:规则只有从未发布的 DRAFT 版本 → 现也被分析。
        // 草稿条件 age>20 ∧ age>10 同 AND 组冗余(age>20 蕴含 age>10) → 应产出一条 redundancy,证明草稿已纳入分析。
        when(sceneMapper.findByCode(1L, "scene-1")).thenReturn(scene(5L, DecisionStrategy.HIGHEST_PRIORITY));
        when(ruleDefinitionMapper.findByTenantAndSceneIds(1L, List.of(5L)))
                .thenReturn(List.of(ruleDef(11L, "R_a")));
        AstNode redundant = new AndNode(
                List.of(cond(ConditionTypes.GT, "age", 20), cond(ConditionTypes.GT, "age", 10)),
                null, null);
        when(ruleVersionMapper.findLatestDraft(11L)).thenReturn(draftVersion(11L, redundant));

        RuleSetAnalysisReport report = sut.analyze(1L, "scene-1");

        assertThat(report.redundancies())
                .extracting(f -> f.ruleCode())
                .contains("R_a");
    }

    @Test
    void draft_is_preferred_over_active_when_both_exist() {
        // 同一规则同时有 DRAFT 与 ACTIVE:应分析 DRAFT。
        // 给两规则的 DRAFT 设相交区间 → overlap 命中;若误用 ACTIVE(不相交)则无 overlap。
        when(sceneMapper.findByCode(1L, "scene-1")).thenReturn(scene(5L, DecisionStrategy.HIGHEST_PRIORITY));
        when(ruleDefinitionMapper.findByTenantAndSceneIds(1L, List.of(5L)))
                .thenReturn(List.of(ruleDef(11L, "R_a"), ruleDef(12L, "R_b")));
        // DRAFT 相交(age>10 与 age>20),ACTIVE 不相交(age<0 与 age>100)
        when(ruleVersionMapper.findLatestDraft(11L)).thenReturn(draftVersion(11L, cond(ConditionTypes.GT, "age", 10)));
        when(ruleVersionMapper.findLatestDraft(12L)).thenReturn(draftVersion(12L, cond(ConditionTypes.GT, "age", 20)));

        RuleSetAnalysisReport report = sut.analyze(1L, "scene-1");

        // 命中 overlap 证明分析的是相交的 DRAFT,而非不相交的 ACTIVE;且未回落查 ACTIVE
        assertThat(report.overlaps()).hasSize(1);
        assertThat(report.overlaps().getFirst().locA()).isEqualTo("R_a");
    }
}
