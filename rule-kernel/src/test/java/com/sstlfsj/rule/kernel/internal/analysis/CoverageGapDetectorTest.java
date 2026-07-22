package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.CoverageGapFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.FlowNode;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CoverageGapDetector：决策可达性缺口（绑定了但无规则路径产出）的行为测试。 */
class CoverageGapDetectorTest {

    private static RuleVersionSnapshot.DecisionBinding bind(String decisionCode) {
        return new RuleVersionSnapshot.DecisionBinding(decisionCode, 0);
    }

    private static DecisionLeafNode leaf(String decisionCode) {
        return new DecisionLeafNode(decisionCode, null);
    }

    private static AnalyzableRule rule(String code, AstNode ast, List<String> bound, RuleKind kind) {
        return new AnalyzableRule(code, 1L, ast,
                bound.stream().map(CoverageGapDetectorTest::bind).toList(), kind.tag());
    }

    @Test
    void empty_rules_yield_empty_findings() {
        assertThat(CoverageGapDetector.detect(List.of())).isEmpty();
    }

    @Test
    void decision_tree_binding_unreachable_leaf_yields_gap() {
        // 绑定 {PASS, REVIEW, ESCALATE}，但叶子只产出 {PASS, REVIEW} → ESCALATE 不可达
        AstNode ast = new IfNode(leaf("ignored-cond-placeholder"), leaf("PASS"), leaf("REVIEW"));
        AnalyzableRule tree = rule("R-tree", ast,
                List.of("PASS", "REVIEW", "ESCALATE"), RuleKind.DECISION_TREE);

        List<CoverageGapFinding> findings = CoverageGapDetector.detect(List.of(tree));

        assertThat(findings).hasSize(1);
        CoverageGapFinding f = findings.getFirst();
        assertThat(f.decisionCode()).isEqualTo("ESCALATE");
        assertThat(f.severity()).isEqualTo(Severity.WARN);
        assertThat(f.reason()).contains("ESCALATE").contains("不可达");
    }

    @Test
    void ast_boolean_rule_has_no_gap_because_bound_equals_producible() {
        // AST_BOOLEAN 命中即产出其全部绑定 → 绑定==可产出，无缺口
        AnalyzableRule bool = rule("R-bool", leaf("ignored"),
                List.of("BLOCK"), RuleKind.AST_BOOLEAN);

        assertThat(CoverageGapDetector.detect(List.of(bool))).isEmpty();
    }

    @Test
    void decision_declared_by_one_rule_producible_by_another_is_not_a_gap() {
        // A 只声明（绑定）SHARED 但不产出；B 产出 SHARED → union vs union，不算缺口
        AnalyzableRule a = rule("R-a",
                new IfNode(leaf("c"), leaf("PASS"), null),
                List.of("PASS", "SHARED"), RuleKind.DECISION_TREE);
        AnalyzableRule b = rule("R-b",
                new IfNode(leaf("c"), leaf("SHARED"), null),
                List.of("SHARED"), RuleKind.DECISION_TREE);

        assertThat(CoverageGapDetector.detect(List.of(a, b))).isEmpty();
    }

    @Test
    void decision_table_rows_emit_subset_of_bindings_yields_gap() {
        // 表只在行里产出 {APPROVE}，但绑定了 {APPROVE, DENY} → DENY 不可达
        DecisionTableNode table = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("score", "GT")),
                List.of(new DecisionTableNode.Row(List.of(700), "APPROVE")));
        AnalyzableRule rule = rule("R-table", table,
                List.of("APPROVE", "DENY"), RuleKind.DECISION_TABLE);

        List<CoverageGapFinding> findings = CoverageGapDetector.detect(List.of(rule));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().decisionCode()).isEqualTo("DENY");
        assertThat(findings.getFirst().severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void findings_are_sorted_by_decision_code() {
        // 两个不可达决策 ZULU/ALPHA → 按 decisionCode 升序输出
        AstNode ast = new IfNode(leaf("c"), leaf("PASS"), null);
        AnalyzableRule rule = rule("R-tree", ast,
                List.of("PASS", "ZULU", "ALPHA"), RuleKind.DECISION_TREE);

        List<CoverageGapFinding> findings = CoverageGapDetector.detect(List.of(rule));

        assertThat(findings).extracting(CoverageGapFinding::decisionCode)
                .containsExactly("ALPHA", "ZULU");
    }

    @Test
    void malformed_binding_with_null_or_blank_decision_code_does_not_throw_or_emit_phantom_gap() {
        // 畸形 binding（decisionCode 为 null / blank）：两侧一致过滤，既不抛 NPE 也不产出 null/"" 的假缺口
        AstNode ast = new IfNode(leaf("c"), leaf("PASS"), null);
        AnalyzableRule rule = new AnalyzableRule("R-malformed", 1L, ast,
                List.of(
                        new RuleVersionSnapshot.DecisionBinding("PASS", 0),
                        new RuleVersionSnapshot.DecisionBinding(null, 0),
                        new RuleVersionSnapshot.DecisionBinding("", 0)),
                RuleKind.DECISION_TREE.tag());

        List<CoverageGapFinding> findings = CoverageGapDetector.detect(List.of(rule));

        assertThat(findings).isEmpty();
    }

    @Test
    void expression_script_kind_falls_back_to_bindings_and_yields_no_gap() {
        // EXPRESSION_SCRIPT 无法内省脚本输出 → 保守退回绑定，全部绑定视为可产出，零缺口
        AnalyzableRule script = rule("R-script", leaf("ignored"),
                List.of("S1", "S2", "S3"), RuleKind.EXPRESSION_SCRIPT);

        assertThat(CoverageGapDetector.detect(List.of(script))).isEmpty();
    }

    @Test
    void decision_flow_collects_output_codes_as_producible_so_only_unbound_output_is_gap() {
        // 图产出 {PASS, REVIEW}(两个 OutputNode)，绑定 {PASS, REVIEW, GHOST} → 仅 GHOST 不可达。
        // 若 DECISION_FLOW 落空未收集 Output 码，producible 会为空、误报 PASS/REVIEW 也不可达 —— 此断言正是防落空回归。
        List<FlowNode> nodes = List.of(
                new RuleRefNode("in", "leaf-rule"),
                new OutputNode("o1", "PASS"),
                new OutputNode("o2", "REVIEW"));
        FlowGraph flow = new FlowGraph(nodes, List.of(), "in");
        AnalyzableRule rule = new AnalyzableRule("F-flow", 1L, null,
                List.of(bind("PASS"), bind("REVIEW"), bind("GHOST")),
                RuleKind.DECISION_FLOW.tag(), flow);

        List<CoverageGapFinding> findings = CoverageGapDetector.detect(List.of(rule));

        assertThat(findings).extracting(CoverageGapFinding::decisionCode).containsExactly("GHOST");
    }

    @Test
    void decision_flow_with_all_outputs_bound_yields_no_gap() {
        // 绑定 == Output 码 → 无缺口
        List<FlowNode> nodes = List.of(
                new RuleRefNode("in", "leaf-rule"),
                new OutputNode("o1", "APPROVE"));
        FlowGraph flow = new FlowGraph(nodes, List.of(), "in");
        AnalyzableRule rule = new AnalyzableRule("F-flow", 1L, null,
                List.of(bind("APPROVE")), RuleKind.DECISION_FLOW.tag(), flow);

        assertThat(CoverageGapDetector.detect(List.of(rule))).isEmpty();
    }

    @Test
    void decision_flow_without_graph_falls_back_to_bindings() {
        // flowGraph 缺失(畸形草稿)→ 保守退回绑定，绑定全视为可产出，零缺口
        AnalyzableRule rule = new AnalyzableRule("F-flow", 1L, null,
                List.of(bind("A"), bind("B")), RuleKind.DECISION_FLOW.tag(), null);

        assertThat(CoverageGapDetector.detect(List.of(rule))).isEmpty();
    }

    @Test
    void unknown_kind_string_falls_back_to_bindings_and_does_not_crash() {
        // 未知 kind 串：不崩溃，保守退回绑定，零缺口
        AnalyzableRule weird = new AnalyzableRule("R-weird", 1L, leaf("ignored"),
                List.of(bind("X1"), bind("X2")), "TOTALLY_UNKNOWN_KIND");

        assertThat(CoverageGapDetector.detect(List.of(weird))).isEmpty();
    }
}
