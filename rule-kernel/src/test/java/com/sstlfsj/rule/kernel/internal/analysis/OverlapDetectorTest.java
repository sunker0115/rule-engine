package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.OverlapFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OverlapDetector：相交+同决策 → INFO 提示；不相交 / 异决策 / UNKNOWN → 不报。 */
class OverlapDetectorTest {

    private static ConditionNode cond(String type, String metric, Object threshold) {
        return new ConditionNode(type, metric, null,
                Map.of(ConditionParams.THRESHOLD, threshold), 0.0, null, ValueRef.METRIC);
    }

    private static AnalyzableRule rule(String code, AstNode ast, String decision, int priority) {
        return new AnalyzableRule(code, 1L, ast,
                List.of(new RuleVersionSnapshot.DecisionBinding(decision, priority)),
                RuleKind.AST_BOOLEAN.tag());
    }

    @Test
    void intersecting_same_decision_emits_one_info() {
        // age>10 与 age>20 区间相交，决策同为 D_PASS
        AnalyzableRule a = rule("R_a", cond(ConditionTypes.GT, "age", 10), "D_PASS", 1);
        AnalyzableRule b = rule("R_b", cond(ConditionTypes.GT, "age", 20), "D_PASS", 1);

        List<OverlapFinding> findings = OverlapDetector.detect(List.of(a, b), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(findings).hasSize(1);
        OverlapFinding f = findings.getFirst();
        assertThat(f.locA()).isEqualTo("R_a");
        assertThat(f.locB()).isEqualTo("R_b");
        assertThat(f.severity()).isEqualTo(Severity.INFO);
        assertThat(f.reason()).contains("D_PASS");
    }

    @Test
    void disjoint_cubes_emit_nothing() {
        // age<10 与 age>20 不相交
        AnalyzableRule a = rule("R_a", cond(ConditionTypes.LT, "age", 10), "D_PASS", 1);
        AnalyzableRule b = rule("R_b", cond(ConditionTypes.GT, "age", 20), "D_PASS", 1);

        assertThat(OverlapDetector.detect(List.of(a, b), SceneExecutionStrategy.HIGHEST_PRIORITY)).isEmpty();
    }

    @Test
    void intersecting_different_decision_is_not_overlaps_job() {
        // 相交但决策不同 → 属冲突检测，重叠检测不报
        AnalyzableRule a = rule("R_a", cond(ConditionTypes.GT, "age", 10), "D_PASS", 1);
        AnalyzableRule b = rule("R_b", cond(ConditionTypes.GT, "age", 20), "D_DENY", 1);

        assertThat(OverlapDetector.detect(List.of(a, b), SceneExecutionStrategy.HIGHEST_PRIORITY)).isEmpty();
    }

    @Test
    void unknown_overlap_is_skipped() {
        // name MATCHES 降级 unknown，区间相交不可判定 → 跳过（零误报）
        ConditionNode regexA = new ConditionNode(ConditionTypes.MATCHES, "name", null,
                Map.of(ConditionParams.REGEX, "^A.*"), 0.0, null, ValueRef.METRIC);
        ConditionNode regexB = new ConditionNode(ConditionTypes.MATCHES, "name", null,
                Map.of(ConditionParams.REGEX, "^B.*"), 0.0, null, ValueRef.METRIC);
        AnalyzableRule a = rule("R_a", regexA, "D_PASS", 1);
        AnalyzableRule b = rule("R_b", regexB, "D_PASS", 1);

        assertThat(OverlapDetector.detect(List.of(a, b), SceneExecutionStrategy.HIGHEST_PRIORITY)).isEmpty();
    }
}
