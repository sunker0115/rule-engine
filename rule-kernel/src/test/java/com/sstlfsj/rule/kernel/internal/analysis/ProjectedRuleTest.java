package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** ProjectedRule：投影 + 有效决策（最高优先级绑定）选取行为测试。 */
class ProjectedRuleTest {

    private static ConditionNode cond(String type, String metric, Object threshold) {
        return new ConditionNode(type, metric, null,
                Map.of(ConditionParams.THRESHOLD, threshold), 0.0, null, ValueRef.METRIC);
    }

    private static AnalyzableRule rule(String code, AstNode ast,
                                       List<RuleVersionSnapshot.DecisionBinding> bindings, String kind) {
        return new AnalyzableRule(code, 1L, ast, bindings, kind);
    }

    @Test
    void picks_binding_with_highest_priority() {
        AnalyzableRule r = rule("R1", cond(ConditionTypes.GT, "age", 18), List.of(
                new RuleVersionSnapshot.DecisionBinding("D_LOW", 1),
                new RuleVersionSnapshot.DecisionBinding("D_HIGH", 9),
                new RuleVersionSnapshot.DecisionBinding("D_MID", 5)),
                RuleKind.AST_BOOLEAN.tag());

        Optional<ProjectedRule> projected = ProjectedRule.of(r);

        assertThat(projected).isPresent();
        assertThat(projected.get().effectiveDecisionCode()).isEqualTo("D_HIGH");
        assertThat(projected.get().effectivePriority()).isEqualTo(9);
    }

    @Test
    void tie_priority_keeps_first_in_list() {
        AnalyzableRule r = rule("R1", cond(ConditionTypes.GT, "age", 18), List.of(
                new RuleVersionSnapshot.DecisionBinding("D_FIRST", 5),
                new RuleVersionSnapshot.DecisionBinding("D_SECOND", 5)),
                RuleKind.AST_BOOLEAN.tag());

        // 优先级相等 → 取列表中靠前者（稳定）
        assertThat(ProjectedRule.of(r)).get()
                .extracting(ProjectedRule::effectiveDecisionCode).isEqualTo("D_FIRST");
    }

    @Test
    void empty_when_no_bindings() {
        AnalyzableRule r = rule("R1", cond(ConditionTypes.GT, "age", 18), List.of(),
                RuleKind.AST_BOOLEAN.tag());
        // 无绑定 → 无法推断决策 → 空
        assertThat(ProjectedRule.of(r)).isEmpty();
    }

    @Test
    void empty_when_unprojectable() {
        AstNode ast = new OrNode(List.of(
                cond(ConditionTypes.GT, "age", 18),
                cond(ConditionTypes.LT, "amount", 1000)), null, null);
        AnalyzableRule r = rule("R1", ast,
                List.of(new RuleVersionSnapshot.DecisionBinding("D", 1)), RuleKind.AST_BOOLEAN.tag());
        // OR 根不可投影 → 空，即便有绑定
        assertThat(ProjectedRule.of(r)).isEmpty();
    }
}
