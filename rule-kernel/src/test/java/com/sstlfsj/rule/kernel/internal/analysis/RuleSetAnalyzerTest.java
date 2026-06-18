package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.RuleSetAnalysisReport;
import com.sstlfsj.rule.kernel.api.analysis.UnanalyzableRule;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RuleSetAnalyzer：编排 6 detector 装配 RuleSetAnalysisReport 的端到端行为测试。
 * 覆盖：可投影对重叠合并、不可投影/非 AST_BOOLEAN 种类归入 unanalyzable（按 kind 给原因）、
 * 决策表行内发现合并入报告、矛盾规则入 incoherences、空输入全空报告、合并列表确定性排序。
 */
class RuleSetAnalyzerTest {

    private static ConditionNode cond(String type, String metric, Object threshold) {
        return new ConditionNode(type, metric, null,
                Map.of(ConditionParams.THRESHOLD, threshold), 0.0, null, ValueRef.METRIC);
    }

    private static AnalyzableRule booleanRule(String code, AstNode ast, String decision, int priority) {
        return new AnalyzableRule(code, 1L, ast,
                List.of(new RuleVersionSnapshot.DecisionBinding(decision, priority)),
                RuleKind.AST_BOOLEAN.tag());
    }

    private static AnalyzableRule rule(String code, AstNode ast, String kind, List<String> decisions) {
        return new AnalyzableRule(code, 1L, ast,
                decisions.stream()
                        .map(d -> new RuleVersionSnapshot.DecisionBinding(d, 1))
                        .toList(),
                kind);
    }

    @Test
    void projectable_overlapping_pair_emits_overlap_and_not_unanalyzable() {
        // R_a age>10 与 R_b age>20 区间相交、同决策 → overlaps 非空；两者均可投影 → 不在 unanalyzable
        AnalyzableRule a = booleanRule("R_a", cond(ConditionTypes.GT, "age", 10), "D_PASS", 1);
        AnalyzableRule b = booleanRule("R_b", cond(ConditionTypes.GT, "age", 20), "D_PASS", 1);

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(a, b), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.sceneCode()).isEqualTo("scene-1");
        assertThat(report.overlaps()).hasSize(1);
        assertThat(report.overlaps().getFirst().locA()).isEqualTo("R_a");
        assertThat(report.unanalyzableRules()).isEmpty();
    }

    @Test
    void or_root_boolean_rule_is_unanalyzable_with_or_reason() {
        // OR 根 → 不可投影 → unanalyzable，理由为 OR/NOT/嵌套；仍可参与 coverageGaps（声明了不可达决策）
        AstNode or = new OrNode(List.of(
                cond(ConditionTypes.GT, "age", 30),
                cond(ConditionTypes.LT, "age", 10)),
                null, null);
        AnalyzableRule orRule = booleanRule("R_or", or, "D_OR", 1);

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(orRule), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.unanalyzableRules())
                .extracting(UnanalyzableRule::ruleCode)
                .containsExactly("R_or");
        assertThat(report.unanalyzableRules().getFirst().reason())
                .isEqualTo("含 OR/NOT/XOR/嵌套或非扁平 AND，超出 v1 区间分析");
    }

    @Test
    void decision_table_overlapping_rows_merge_into_report_and_table_not_unanalyzable() {
        // 决策表两行相交同决策 → 行内 overlap 合并入 report.overlaps；决策表整体走行内分析 → 不在 unanalyzable
        AnalyzableRule table = new AnalyzableRule("T1", 1L,
                new DecisionTableNode(
                        List.of(new DecisionTableNode.Column("age", ConditionTypes.GT)),
                        List.of(new DecisionTableNode.Row(List.of(10), "D_SAME"),
                                new DecisionTableNode.Row(List.of(20), "D_SAME"))),
                List.of(), RuleKind.DECISION_TABLE.tag());

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(table), SceneExecutionStrategy.FIRST_HIT);

        assertThat(report.overlaps())
                .extracting(of -> of.locA())
                .containsExactly("T1#row1");
        assertThat(report.unanalyzableRules()).isEmpty();
    }

    @Test
    void decision_tree_scorecard_script_rules_are_unanalyzable_with_kind_reasons() {
        // 决策树跨树区间分析 v1 未做
        AnalyzableRule tree = rule("R_tree",
                new IfNode(cond(ConditionTypes.GT, "age", 18),
                        new DecisionLeafNode("D_ADULT", null),
                        new DecisionLeafNode("D_MINOR", null)),
                RuleKind.DECISION_TREE.tag(), List.of());
        // 评分卡按加权分不参与区间分析
        AnalyzableRule card = rule("R_card",
                new ScorecardRootNode(List.of(cond(ConditionTypes.GT, "x", 1)), 5.0, List.of()),
                RuleKind.SCORECARD.tag(), List.of());
        // 脚本无法静态推理
        AnalyzableRule script = rule("R_script",
                cond(ConditionTypes.GT, "age", 1),
                RuleKind.EXPRESSION_SCRIPT.tag(), List.of());

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(tree, card, script), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.unanalyzableRules())
                .extracting(UnanalyzableRule::ruleCode, UnanalyzableRule::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("R_card", "评分卡按加权分，不参与区间分析"),
                        org.assertj.core.groups.Tuple.tuple("R_script", "脚本规则无法静态推理"),
                        org.assertj.core.groups.Tuple.tuple("R_tree", "决策树跨树区间分析 v1 未做"));
    }

    @Test
    void incoherent_boolean_rule_appears_in_incoherences() {
        // age>30 ∧ age<10 → 维度空集，自相矛盾 → incoherences 非空
        AstNode bad = new AndNode(List.of(
                cond(ConditionTypes.GT, "age", 30),
                cond(ConditionTypes.LT, "age", 10)),
                null, null);

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(booleanRule("R_bad", bad, "D_X", 1)),
                SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.incoherences())
                .extracting(f -> f.ruleCode())
                .containsExactly("R_bad");
    }

    @Test
    void decision_table_incoherent_row_merges_into_report_incoherences_sorted() {
        // 决策表 T1 行1 age>50 ∧ age<5 列矛盾 → 行内 incoherence "T1#row1"；
        // AST_BOOLEAN R_bad age>30 ∧ age<10 自相矛盾 → "R_bad"。两源合并后按 ruleCode 升序：R_bad < T1#row1
        AstNode bad = new AndNode(List.of(
                cond(ConditionTypes.GT, "age", 30),
                cond(ConditionTypes.LT, "age", 10)),
                null, null);
        AnalyzableRule table = new AnalyzableRule("T1", 1L,
                new DecisionTableNode(
                        List.of(new DecisionTableNode.Column("age", ConditionTypes.GT),
                                new DecisionTableNode.Column("age", ConditionTypes.LT)),
                        List.of(new DecisionTableNode.Row(List.of(50, 5), "D_A"))),
                List.of(), RuleKind.DECISION_TABLE.tag());

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(table, booleanRule("R_bad", bad, "D_X", 1)),
                SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.incoherences())
                .extracting(f -> f.ruleCode())
                .containsExactly("R_bad", "T1#row1");
    }

    @Test
    void rule_with_redundant_conditions_populates_redundancies() {
        // amount <= 10 AND amount == 10 → <= 10 被 == 10 蕴含 → redundancies 非空
        AstNode redundant = new AndNode(List.of(
                cond(ConditionTypes.LTE, "amount", 10),
                cond(ConditionTypes.EQ, "amount", 10)),
                null, null);

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(booleanRule("R_red", redundant, "D_X", 1)),
                SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.redundancies())
                .extracting(f -> f.ruleCode(), f -> f.redundantCondition(), f -> f.impliedByCondition())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "R_red", "amount LTE 10", "amount EQ 10"));
    }

    @Test
    void decision_table_redundant_row_merges_into_redundancies_sorted() {
        // AST_BOOLEAN R_red：amount<=10 ∧ amount==10 → "R_red" 行内冗余；
        // 决策表 T_tab 行 amount<=10 + amount==10 → "T_tab#row1" 行内冗余。
        // 两源合并入 report.redundancies，按 (ruleCode, redundantCondition) 升序：R_red < T_tab#row1
        AstNode redundant = new AndNode(List.of(
                cond(ConditionTypes.LTE, "amount", 10),
                cond(ConditionTypes.EQ, "amount", 10)),
                null, null);
        AnalyzableRule astRule = booleanRule("R_red", redundant, "D_X", 1);
        AnalyzableRule tableRule = new AnalyzableRule("T_tab", 1L,
                new DecisionTableNode(
                        List.of(new DecisionTableNode.Column("amount", ConditionTypes.LTE),
                                new DecisionTableNode.Column("amount", ConditionTypes.EQ)),
                        List.of(new DecisionTableNode.Row(List.of(10, 10), "D_A"))),
                List.of(), RuleKind.DECISION_TABLE.tag());

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(astRule, tableRule), SceneExecutionStrategy.FIRST_HIT);

        assertThat(report.redundancies())
                .extracting(f -> f.ruleCode(), f -> f.redundantCondition())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("R_red", "amount LTE 10"),
                        org.assertj.core.groups.Tuple.tuple("T_tab#row1", "amount LTE 10"));
    }

    @Test
    void empty_rules_returns_all_empty_report_with_scene_code() {
        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-empty", List.of(), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.sceneCode()).isEqualTo("scene-empty");
        assertThat(report.incoherences()).isEmpty();
        assertThat(report.deadRules()).isEmpty();
        assertThat(report.conflicts()).isEmpty();
        assertThat(report.overlaps()).isEmpty();
        assertThat(report.coverageGaps()).isEmpty();
        assertThat(report.unanalyzableRules()).isEmpty();
        assertThat(report.redundancies()).isEmpty();
    }

    @Test
    void null_rules_returns_all_empty_report_with_scene_code() {
        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-null", null, SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.sceneCode()).isEqualTo("scene-null");
        assertThat(report.unanalyzableRules()).isEmpty();
        assertThat(report.overlaps()).isEmpty();
    }

    @Test
    void merged_overlaps_from_pairwise_and_table_are_sorted_deterministically() {
        // 决策表 T1（行内 overlap，loc "T1#row1"）+ 可投影对 R_a/R_b（pairwise overlap，loc "R_a"）
        // 合并后按 (locA, locB) 升序：R_a < T1#row1
        AnalyzableRule a = booleanRule("R_a", cond(ConditionTypes.GT, "age", 10), "D_PASS", 1);
        AnalyzableRule b = booleanRule("R_b", cond(ConditionTypes.GT, "age", 20), "D_PASS", 1);
        AnalyzableRule table = new AnalyzableRule("T1", 1L,
                new DecisionTableNode(
                        List.of(new DecisionTableNode.Column("age", ConditionTypes.GT)),
                        List.of(new DecisionTableNode.Row(List.of(10), "D_SAME"),
                                new DecisionTableNode.Row(List.of(20), "D_SAME"))),
                List.of(), RuleKind.DECISION_TABLE.tag());

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(table, a, b), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.overlaps())
                .extracting(of -> of.locA())
                .containsExactly("R_a", "T1#row1");
    }

    @Test
    void unanalyzable_rules_are_sorted_by_rule_code() {
        AnalyzableRule script = rule("Z_script", cond(ConditionTypes.GT, "age", 1),
                RuleKind.EXPRESSION_SCRIPT.tag(), List.of());
        AnalyzableRule card = rule("A_card",
                new ScorecardRootNode(List.of(cond(ConditionTypes.GT, "x", 1)), 5.0, List.of()),
                RuleKind.SCORECARD.tag(), List.of());

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(script, card), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.unanalyzableRules())
                .extracting(UnanalyzableRule::ruleCode)
                .containsExactly("A_card", "Z_script");
    }

    @Test
    void unknown_kind_rule_is_unanalyzable_with_generic_reason() {
        // 未知 kind 标签 → 通用原因 "<kind> 暂不支持静态分析"
        AnalyzableRule weird = new AnalyzableRule("R_weird", 1L,
                cond(ConditionTypes.GT, "age", 1), List.of(), "FUZZY_LOGIC");

        RuleSetAnalysisReport report = RuleSetAnalyzer.analyze(
                "scene-1", List.of(weird), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(report.unanalyzableRules())
                .extracting(UnanalyzableRule::ruleCode, UnanalyzableRule::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "R_weird", "FUZZY_LOGIC 暂不支持静态分析"));
    }
}
