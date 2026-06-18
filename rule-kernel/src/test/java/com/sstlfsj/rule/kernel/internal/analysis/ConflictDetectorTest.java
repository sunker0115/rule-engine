package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.ConflictFinding;
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

/** ConflictDetector：相交+异决策在不同执行策略下的歧义判定。 */
class ConflictDetectorTest {

    private static ConditionNode cond(String type, String metric, Object threshold) {
        return new ConditionNode(type, metric, null,
                Map.of(ConditionParams.THRESHOLD, threshold), 0.0, null, ValueRef.METRIC);
    }

    private static AnalyzableRule rule(String code, AstNode ast, String decision, int priority) {
        return new AnalyzableRule(code, 1L, ast,
                List.of(new RuleVersionSnapshot.DecisionBinding(decision, priority)),
                RuleKind.AST_BOOLEAN.tag());
    }

    /** 相交（age>10 与 age>20）但决策不同。 */
    private static List<AnalyzableRule> intersectingDifferentDecision(int priorityA, int priorityB) {
        return List.of(
                rule("R_a", cond(ConditionTypes.GT, "age", 10), "D_PASS", priorityA),
                rule("R_b", cond(ConditionTypes.GT, "age", 20), "D_DENY", priorityB));
    }

    @Test
    void all_hits_intersecting_different_decision_is_conflict() {
        List<ConflictFinding> findings = ConflictDetector.detect(
                intersectingDifferentDecision(1, 1), SceneExecutionStrategy.ALL_HITS);

        assertThat(findings).hasSize(1);
        ConflictFinding f = findings.getFirst();
        assertThat(f.locA()).isEqualTo("R_a");
        assertThat(f.locB()).isEqualTo("R_b");
        assertThat(f.decisionA()).isEqualTo("D_PASS");
        assertThat(f.decisionB()).isEqualTo("D_DENY");
        assertThat(f.severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void highest_priority_equal_priority_is_conflict() {
        // 相等优先级 → 相交区域内谁胜出歧义 → 冲突
        List<ConflictFinding> findings = ConflictDetector.detect(
                intersectingDifferentDecision(5, 5), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void highest_priority_different_priority_emits_nothing() {
        // 优先级不同 → 高者确定性胜出 → 非冲突
        assertThat(ConflictDetector.detect(
                intersectingDifferentDecision(9, 1), SceneExecutionStrategy.HIGHEST_PRIORITY)).isEmpty();
    }

    @Test
    void first_hit_emits_nothing() {
        // FIRST_HIT：在先规则确定性赢得相交区域，无歧义 → 不报
        assertThat(ConflictDetector.detect(
                intersectingDifferentDecision(5, 5), SceneExecutionStrategy.FIRST_HIT)).isEmpty();
    }

    @Test
    void disjoint_cubes_emit_nothing() {
        // 不相交 → 无冲突，即便决策不同
        List<AnalyzableRule> rules = List.of(
                rule("R_a", cond(ConditionTypes.LT, "age", 10), "D_PASS", 5),
                rule("R_b", cond(ConditionTypes.GT, "age", 20), "D_DENY", 5));
        assertThat(ConflictDetector.detect(rules, SceneExecutionStrategy.ALL_HITS)).isEmpty();
    }

    @Test
    void same_decision_is_not_conflict() {
        // 决策相同属重叠检测职责，冲突检测不报
        List<AnalyzableRule> rules = List.of(
                rule("R_a", cond(ConditionTypes.GT, "age", 10), "D_PASS", 5),
                rule("R_b", cond(ConditionTypes.GT, "age", 20), "D_PASS", 5));
        assertThat(ConflictDetector.detect(rules, SceneExecutionStrategy.ALL_HITS)).isEmpty();
    }

    @Test
    void unknown_overlap_is_skipped() {
        // 区间不可判定 → 跳过（零误报）
        ConditionNode regexA = new ConditionNode(ConditionTypes.MATCHES, "name", null,
                Map.of(ConditionParams.REGEX, "^A.*"), 0.0, null, ValueRef.METRIC);
        ConditionNode regexB = new ConditionNode(ConditionTypes.MATCHES, "name", null,
                Map.of(ConditionParams.REGEX, "^B.*"), 0.0, null, ValueRef.METRIC);
        List<AnalyzableRule> rules = List.of(
                rule("R_a", regexA, "D_PASS", 5),
                rule("R_b", regexB, "D_DENY", 5));
        assertThat(ConflictDetector.detect(rules, SceneExecutionStrategy.ALL_HITS)).isEmpty();
    }
}
