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
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    void scene_with_two_active_rules_overlap_is_reported() {
        // R_a age>10 与 R_b age>20 区间相交、同决策 → 应得到一条 overlap,验证拆 AnalyzableRule + 调分析器接线正确
        when(sceneMapper.findByCode(1L, "scene-1")).thenReturn(scene(5L, DecisionStrategy.HIGHEST_PRIORITY));
        RuleDefinition rdA = ruleDef(11L, "R_a");
        RuleDefinition rdB = ruleDef(12L, "R_b");
        when(ruleDefinitionMapper.findByTenantAndSceneIds(1L, List.of(5L))).thenReturn(List.of(rdA, rdB));
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

    @Test
    void rule_definition_without_active_version_is_skipped() {
        // 规则定义存在但无 ACTIVE 版本(findActiveVersion 返回 null) → 跳过,不入分析,返回空报告
        when(sceneMapper.findByCode(1L, "scene-1")).thenReturn(scene(5L, DecisionStrategy.HIGHEST_PRIORITY));
        when(ruleDefinitionMapper.findByTenantAndSceneIds(1L, List.of(5L)))
                .thenReturn(List.of(ruleDef(11L, "R_a")));
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(null);

        RuleSetAnalysisReport report = sut.analyze(1L, "scene-1");

        assertThat(report.overlaps()).isEmpty();
        assertThat(report.unanalyzableRules()).isEmpty();
    }
}
