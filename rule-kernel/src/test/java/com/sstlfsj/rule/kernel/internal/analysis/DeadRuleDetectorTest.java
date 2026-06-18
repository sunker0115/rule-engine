package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.DeadRuleFinding;
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

/** DeadRuleDetector：高优先级规则完全覆盖低优先级规则 → 后者死规则；含策略 / 等优先级 / 部分相交 / UNKNOWN 降级。 */
class DeadRuleDetectorTest {

    private static ConditionNode cond(String type, String metric, Object threshold) {
        return new ConditionNode(type, metric, null,
                Map.of(ConditionParams.THRESHOLD, threshold), 0.0, null, ValueRef.METRIC);
    }

    private static AnalyzableRule rule(String code, AstNode ast, String decision, int priority) {
        return new AnalyzableRule(code, 1L, ast,
                List.of(new RuleVersionSnapshot.DecisionBinding(decision, priority)),
                RuleKind.AST_BOOLEAN.tag());
    }

    /** A: age>10（宽，⊇ B），B: age in [20,30]（窄）。 */
    private static AnalyzableRule wide(String code, int priority) {
        return rule(code, cond(ConditionTypes.GT, "age", 10), "D_A", priority);
    }

    private static AnalyzableRule narrow(String code, int priority) {
        ConditionNode between = new ConditionNode(ConditionTypes.BETWEEN, "age", null,
                Map.of(ConditionParams.MIN, 20, ConditionParams.MAX, 30), 0.0, null, ValueRef.METRIC);
        return rule(code, between, "D_B", priority);
    }

    @Test
    void highest_priority_higher_subsuming_rule_kills_lower() {
        // A(priority 9) ⊇ B(priority 1) → B 死
        AnalyzableRule a = wide("R_a", 9);
        AnalyzableRule b = narrow("R_b", 1);

        List<DeadRuleFinding> findings = DeadRuleDetector.detect(List.of(a, b), SceneExecutionStrategy.HIGHEST_PRIORITY);

        assertThat(findings).hasSize(1);
        DeadRuleFinding f = findings.getFirst();
        assertThat(f.deadRuleCode()).isEqualTo("R_b");
        assertThat(f.coveredByRuleCode()).isEqualTo("R_a");
        assertThat(f.severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void all_hits_emits_nothing() {
        // ALL_HITS 全量收集，无掩盖
        assertThat(DeadRuleDetector.detect(
                List.of(wide("R_a", 9), narrow("R_b", 1)), SceneExecutionStrategy.ALL_HITS)).isEmpty();
    }

    @Test
    void equal_priority_subsumption_is_not_dead() {
        // 等优先级 subsumption 不报（HIGHEST_PRIORITY 下属冲突，非死规则）
        assertThat(DeadRuleDetector.detect(
                List.of(wide("R_a", 5), narrow("R_b", 5)), SceneExecutionStrategy.HIGHEST_PRIORITY)).isEmpty();
    }

    @Test
    void partial_overlap_neither_subsumes_emits_nothing() {
        // age<50 与 age>20 相交于 (20,50) 但互不包含（仅部分相交）→ 无死规则
        AnalyzableRule c = rule("R_c", cond(ConditionTypes.LT, "age", 50), "D_C", 9);
        AnalyzableRule d = rule("R_d", cond(ConditionTypes.GT, "age", 20), "D_D", 1);
        assertThat(DeadRuleDetector.detect(List.of(c, d), SceneExecutionStrategy.HIGHEST_PRIORITY)).isEmpty();
    }

    @Test
    void unknown_subsumption_degrades_to_nothing() {
        // 多一维 unknown，subsumes 降级 UNKNOWN → 跳过
        ConditionNode wideAge = cond(ConditionTypes.GT, "age", 10);
        ConditionNode regex = new ConditionNode(ConditionTypes.MATCHES, "name", null,
                Map.of(ConditionParams.REGEX, "^A.*"), 0.0, null, ValueRef.METRIC);
        ConditionNode narrowAge = new ConditionNode(ConditionTypes.BETWEEN, "age", null,
                Map.of(ConditionParams.MIN, 20, ConditionParams.MAX, 30), 0.0, null, ValueRef.METRIC);

        AnalyzableRule a = new AnalyzableRule("R_a", 1L,
                new com.sstlfsj.rule.kernel.api.model.ast.AndNode(List.of(wideAge, regex), null, null),
                List.of(new RuleVersionSnapshot.DecisionBinding("D_A", 9)), RuleKind.AST_BOOLEAN.tag());
        AnalyzableRule b = new AnalyzableRule("R_b", 1L,
                new com.sstlfsj.rule.kernel.api.model.ast.AndNode(List.of(narrowAge, regex), null, null),
                List.of(new RuleVersionSnapshot.DecisionBinding("D_B", 1)), RuleKind.AST_BOOLEAN.tag());

        assertThat(DeadRuleDetector.detect(List.of(a, b), SceneExecutionStrategy.HIGHEST_PRIORITY)).isEmpty();
    }

    @Test
    void first_hit_higher_subsuming_rule_kills_lower() {
        // FIRST_HIT 同样掩盖：A ⊇ B 且 A 优先级更高 → B 死
        assertThat(DeadRuleDetector.detect(
                List.of(wide("R_a", 9), narrow("R_b", 1)), SceneExecutionStrategy.FIRST_HIT)).hasSize(1);
    }
}
