package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.ConflictFinding;
import com.sstlfsj.rule.kernel.api.analysis.DeadRuleFinding;
import com.sstlfsj.rule.kernel.api.analysis.IncoherenceFinding;
import com.sstlfsj.rule.kernel.api.analysis.OverlapFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DecisionTableDetector：决策表行内分析（同一表内行对的相交重叠 / 决策冲突 / FIRST_HIT 掩盖死行）。
 * 覆盖同决策重叠、异决策冲突、被在先行包含的死行、互斥行无发现、不可建模单元格降级。
 */
class DecisionTableDetectorTest {

    private static DecisionTableNode.Column col(String metric, String operator) {
        return new DecisionTableNode.Column(metric, operator);
    }

    private static DecisionTableNode.Row row(String decision, Object... cells) {
        return new DecisionTableNode.Row(Arrays.asList(cells), decision);
    }

    private static AnalyzableRule table(String code, List<DecisionTableNode.Column> columns,
                                        List<DecisionTableNode.Row> rows) {
        return new AnalyzableRule(code, 1L, new DecisionTableNode(columns, rows),
                List.of(), RuleKind.DECISION_TABLE.tag());
    }

    @Test
    void two_rows_same_decision_overlapping_inputs_emit_overlap() {
        // 列 age GT；行1 age>10、行2 age>20 相交（>20 ⊆ >10），同决策 → 1 条 overlap
        AnalyzableRule t = table("T1", List.of(col("age", ConditionTypes.GT)),
                List.of(row("D_SAME", 10), row("D_SAME", 20)));

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(t));

        assertThat(r.overlaps()).hasSize(1);
        OverlapFinding f = r.overlaps().getFirst();
        assertThat(f.locA()).isEqualTo("T1#row1");
        assertThat(f.locB()).isEqualTo("T1#row2");
        assertThat(f.severity()).isEqualTo(Severity.INFO);
        assertThat(r.conflicts()).isEmpty();
    }

    @Test
    void two_rows_different_decision_overlapping_inputs_emit_conflict() {
        // 行1 age>10 / 行2 age>20 相交，决策不同 → 1 条 conflict
        AnalyzableRule t = table("T2", List.of(col("age", ConditionTypes.GT)),
                List.of(row("D_A", 10), row("D_B", 20)));

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(t));

        assertThat(r.conflicts()).hasSize(1);
        ConflictFinding f = r.conflicts().getFirst();
        assertThat(f.locA()).isEqualTo("T2#row1");
        assertThat(f.locB()).isEqualTo("T2#row2");
        assertThat(f.decisionA()).isEqualTo("D_A");
        assertThat(f.decisionB()).isEqualTo("D_B");
        assertThat(f.severity()).isEqualTo(Severity.WARN);
        assertThat(r.overlaps()).isEmpty();
    }

    @Test
    void later_row_subsumed_by_earlier_row_is_dead_under_first_hit() {
        // 行1 age>10（宽）⊇ 行2 age in [20,30]（窄）→ FIRST_HIT 下行2 永不命中（死行）
        AnalyzableRule t = table("T3", List.of(col("age", ConditionTypes.GT), col("age", ConditionTypes.BETWEEN)),
                List.of(
                        new DecisionTableNode.Row(Arrays.asList(10, null), "D_A"),
                        new DecisionTableNode.Row(Arrays.asList(null, List.of(20, 30)), "D_B")));

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(t));

        assertThat(r.deadRows()).hasSize(1);
        DeadRuleFinding f = r.deadRows().getFirst();
        assertThat(f.deadRuleCode()).isEqualTo("T3#row2");
        assertThat(f.coveredByRuleCode()).isEqualTo("T3#row1");
        assertThat(f.severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void disjoint_rows_emit_no_findings() {
        // 行1 age<10 / 行2 age>20 互斥 → 无任何发现
        AnalyzableRule t = table("T4", List.of(col("age", ConditionTypes.LT), col("age", ConditionTypes.GT)),
                List.of(
                        new DecisionTableNode.Row(Arrays.asList(10, null), "D_A"),
                        new DecisionTableNode.Row(Arrays.asList(null, 20), "D_B")));

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(t));

        assertThat(r.overlaps()).isEmpty();
        assertThat(r.conflicts()).isEmpty();
        assertThat(r.deadRows()).isEmpty();
    }

    @Test
    void unmodelable_cell_degrades_with_no_false_finding() {
        // 列 name MATCHES（正则，不可建模）→ 两行该维度 UNKNOWN，overlaps/subsumes 降级 → 零误报
        AnalyzableRule t = table("T5", List.of(col("name", ConditionTypes.MATCHES)),
                List.of(row("D_A", "^A.*"), row("D_B", "^B.*")));

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(t));

        assertThat(r.overlaps()).isEmpty();
        assertThat(r.conflicts()).isEmpty();
        assertThat(r.deadRows()).isEmpty();
    }

    @Test
    void same_metric_two_columns_meet_narrows_dimension() {
        // 同一行内 age@METRIC 两列都非 wildcard：行1 (age>10 ∧ age<50) meet 收窄为 (10,50)；
        // 行2 age in [20,30] ⊆ (10,50) → 行1 ⊇ 行2 真相交。验证 dims.merge(meet) 走真实交集路径。
        AnalyzableRule t = table("TM1",
                List.of(col("age", ConditionTypes.GT), col("age", ConditionTypes.LT), col("age", ConditionTypes.BETWEEN)),
                List.of(
                        new DecisionTableNode.Row(Arrays.asList(10, 50, null), "D_SAME"),
                        new DecisionTableNode.Row(Arrays.asList(null, null, List.of(20, 30)), "D_SAME")));

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(t));

        // 收窄维 (10,50) 与 [20,30] 相交且同决策 → overlap；且 (10,50) ⊇ [20,30] → 行2 死行
        assertThat(r.overlaps()).hasSize(1);
        assertThat(r.overlaps().getFirst().locA()).isEqualTo("TM1#row1");
        assertThat(r.deadRows()).hasSize(1);
        assertThat(r.deadRows().getFirst().deadRuleCode()).isEqualTo("TM1#row2");
    }

    @Test
    void same_metric_two_columns_meet_contradiction_yields_empty_dimension() {
        // 行1 age>50 ∧ age<5 → meet 为空维（该行恒不命中）→ 行内不一致 incoherence。
        // 空维行不参与与正常行2 (age in [10,20]) 的两两比较 → 无 overlap / conflict / dead（零误报）。
        AnalyzableRule t = table("TM2",
                List.of(col("age", ConditionTypes.GT), col("age", ConditionTypes.LT), col("age", ConditionTypes.BETWEEN)),
                List.of(
                        new DecisionTableNode.Row(Arrays.asList(50, 5, null), "D_A"),
                        new DecisionTableNode.Row(Arrays.asList(null, null, List.of(10, 20)), "D_B")));

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(t));

        // 矛盾行 → ERROR 级 incoherence，loc 为 TM2#row1
        assertThat(r.incoherences()).hasSize(1);
        IncoherenceFinding inc = r.incoherences().getFirst();
        assertThat(inc.ruleCode()).isEqualTo("TM2#row1");
        assertThat(inc.severity()).isEqualTo(Severity.ERROR);
        assertThat(inc.reason()).contains("TM2#row1");
        // 恒不命中的空维行不参与两两比较 → 无 overlap / conflict / dead 发现（零误报）
        assertThat(r.overlaps()).isEmpty();
        assertThat(r.conflicts()).isEmpty();
        assertThat(r.deadRows()).isEmpty();
    }

    @Test
    void coherent_rows_emit_no_incoherence() {
        // 列条件不矛盾的行不产生 incoherence
        AnalyzableRule t = table("TM3", List.of(col("age", ConditionTypes.GT)),
                List.of(row("D_A", 10), row("D_B", 20)));

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(t));

        assertThat(r.incoherences()).isEmpty();
    }

    @Test
    void non_decision_table_rule_is_skipped() {
        // 非决策表规则（AST_BOOLEAN）不参与行内分析
        ConditionNode c = new ConditionNode(ConditionTypes.GT, "age", null, Map.of("threshold", 10), 0.0);
        AnalyzableRule notTable = new AnalyzableRule("R1", 1L, c, List.of(), RuleKind.AST_BOOLEAN.tag());

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(notTable));

        assertThat(r.overlaps()).isEmpty();
        assertThat(r.conflicts()).isEmpty();
        assertThat(r.deadRows()).isEmpty();
    }

    @Test
    void wildcard_rows_overlap_full_domain() {
        // 全通配行（所有单元格 null）→ 立方体为全集 any，与任意行相交
        AnalyzableRule t = table("T6", List.of(col("age", ConditionTypes.GT)),
                List.of(
                        new DecisionTableNode.Row(Arrays.asList((Object) null), "D_A"),
                        row("D_A", 20)));

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(t));

        // 行1（全集）⊇ 行2（age>20）→ 既相交（同决策→overlap）又掩盖（死行）
        assertThat(r.overlaps()).hasSize(1);
        assertThat(r.deadRows()).hasSize(1);
        assertThat(r.deadRows().getFirst().deadRuleCode()).isEqualTo("T6#row2");
    }

    @Test
    void astnode_not_decision_table_node_is_skipped() {
        // kind 标 DECISION_TABLE 但 AST 实际非 DecisionTableNode → 安全跳过，不抛异常
        ConditionNode c = new ConditionNode(ConditionTypes.GT, "age", null, Map.of("threshold", 10), 0.0);
        AnalyzableRule mismatched = new AnalyzableRule("R2", 1L, c, List.of(), RuleKind.DECISION_TABLE.tag());

        DecisionTableDetector.DecisionTableFindings r = DecisionTableDetector.detect(List.of(mismatched));

        assertThat(r.overlaps()).isEmpty();
        assertThat(r.conflicts()).isEmpty();
        assertThat(r.deadRows()).isEmpty();
    }
}
