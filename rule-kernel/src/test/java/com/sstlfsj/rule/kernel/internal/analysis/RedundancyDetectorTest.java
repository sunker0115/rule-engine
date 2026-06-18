package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.RedundancyFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedundancyDetector：单规则内同 AND 组冗余条件检测。
 * 覆盖：AST_BOOLEAN 蕴含冗余 / 精确重复 / 不同维度无冗余 / 同维互不蕴含无冗余、
 * DECISION_TREE IfNode 条件组冗余、UNKNOWN 空间（正则）零误报。
 */
class RedundancyDetectorTest {

    private static ConditionNode cond(String type, String metric, Object threshold) {
        return new ConditionNode(type, metric, null,
                Map.of(ConditionParams.THRESHOLD, threshold), 0.0, null, ValueRef.METRIC);
    }

    private static AnalyzableRule rule(String code, AstNode ast, String kind) {
        return new AnalyzableRule(code, 1L, ast,
                List.of(new RuleVersionSnapshot.DecisionBinding("D", 1)), kind);
    }

    @Test
    void boolean_and_group_with_implied_condition_flags_redundancy() {
        // amount <= 10 AND amount == 10 → <= 10 被 == 10 蕴含（后者更严格）→ <= 10 冗余
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.LTE, "amount", 10),
                cond(ConditionTypes.EQ, "amount", 10)),
                null, null);

        List<RedundancyFinding> findings = RedundancyDetector.detect(
                List.of(rule("R1", ast, RuleKind.AST_BOOLEAN.tag())));

        assertThat(findings).hasSize(1);
        RedundancyFinding f = findings.getFirst();
        assertThat(f.ruleCode()).isEqualTo("R1");
        assertThat(f.redundantCondition()).isEqualTo("amount LTE 10");
        assertThat(f.impliedByCondition()).isEqualTo("amount EQ 10");
        assertThat(f.severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void boolean_and_group_with_exact_duplicate_flags_redundancy_once() {
        // amount > 5 AND amount > 5 → 精确重复，报告一次（靠后的为冗余）
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.GT, "amount", 5),
                cond(ConditionTypes.GT, "amount", 5)),
                null, null);

        List<RedundancyFinding> findings = RedundancyDetector.detect(
                List.of(rule("R1", ast, RuleKind.AST_BOOLEAN.tag())));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().redundantCondition()).isEqualTo("amount GT 5");
        assertThat(findings.getFirst().impliedByCondition()).isEqualTo("amount GT 5");
    }

    @Test
    void different_dimensions_yield_no_redundancy() {
        // amount > 5 AND age < 30 → 不同维度，互不蕴含
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.GT, "amount", 5),
                cond(ConditionTypes.LT, "age", 30)),
                null, null);

        assertThat(RedundancyDetector.detect(
                List.of(rule("R1", ast, RuleKind.AST_BOOLEAN.tag())))).isEmpty();
    }

    @Test
    void same_dimension_but_neither_implies_yields_no_redundancy() {
        // amount > 5 AND amount < 100 → 同维但互不蕴含（区间相交不互含）
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.GT, "amount", 5),
                cond(ConditionTypes.LT, "amount", 100)),
                null, null);

        assertThat(RedundancyDetector.detect(
                List.of(rule("R1", ast, RuleKind.AST_BOOLEAN.tag())))).isEmpty();
    }

    @Test
    void decision_tree_if_condition_and_group_flags_redundancy() {
        // 决策树 IfNode 条件为 amount <= 10 AND amount == 10 → 用户报告的场景，必须捕获
        AstNode condition = new AndNode(List.of(
                cond(ConditionTypes.LTE, "amount", 10),
                cond(ConditionTypes.EQ, "amount", 10)),
                null, null);
        AstNode tree = new IfNode(condition,
                new DecisionLeafNode("D_HIT", null),
                new DecisionLeafNode("D_MISS", null));

        List<RedundancyFinding> findings = RedundancyDetector.detect(
                List.of(rule("R_tree", tree, RuleKind.DECISION_TREE.tag())));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().redundantCondition()).isEqualTo("amount LTE 10");
        assertThat(findings.getFirst().impliedByCondition()).isEqualTo("amount EQ 10");
    }

    @Test
    void decision_tree_walks_nested_if_branches() {
        // 嵌套 IfNode：内层 then 分支的条件组也要被遍历到
        AstNode innerCondition = new AndNode(List.of(
                cond(ConditionTypes.LTE, "score", 10),
                cond(ConditionTypes.EQ, "score", 10)),
                null, null);
        AstNode inner = new IfNode(innerCondition,
                new DecisionLeafNode("D_A", null), new DecisionLeafNode("D_B", null));
        AstNode tree = new IfNode(cond(ConditionTypes.GT, "amount", 1),
                inner, new DecisionLeafNode("D_C", null));

        List<RedundancyFinding> findings = RedundancyDetector.detect(
                List.of(rule("R_tree", tree, RuleKind.DECISION_TREE.tag())));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().redundantCondition()).isEqualTo("score LTE 10");
    }

    @Test
    void unknown_space_condition_yields_no_false_positive() {
        // MATCHES 正则 → 空间 UNKNOWN，即便同维同算子重复也不报（零误报）
        AstNode ast = new AndNode(List.of(
                new ConditionNode(ConditionTypes.MATCHES, "name", null,
                        Map.of(ConditionParams.THRESHOLD, "^a.*"), 0.0, null, ValueRef.METRIC),
                new ConditionNode(ConditionTypes.MATCHES, "name", null,
                        Map.of(ConditionParams.THRESHOLD, "^a.*"), 0.0, null, ValueRef.METRIC)),
                null, null);

        assertThat(RedundancyDetector.detect(
                List.of(rule("R1", ast, RuleKind.AST_BOOLEAN.tag())))).isEmpty();
    }

    @Test
    void single_condition_root_yields_no_redundancy() {
        // 单条件根（非 AND 组）→ 无组、无冗余
        assertThat(RedundancyDetector.detect(List.of(
                rule("R1", cond(ConditionTypes.GT, "amount", 5), RuleKind.AST_BOOLEAN.tag())))).isEmpty();
    }
}
