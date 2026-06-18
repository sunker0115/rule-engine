package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** CubeProjector：仅「顶层 AND-of-Condition」可投影的投影行为测试。 */
class CubeProjectorTest {

    private static ConditionNode cond(String type, String metric, Map<String, Object> params) {
        return new ConditionNode(type, metric, null, params, 0.0, null, ValueRef.METRIC);
    }

    private static AnalyzableRule rule(AstNode ast, String kind) {
        return new AnalyzableRule("R1", 1L, ast, List.of(), kind);
    }

    private static AnalyzableRule booleanRule(AstNode ast) {
        return rule(ast, RuleKind.AST_BOOLEAN.tag());
    }

    @Test
    void flat_and_of_two_conditions_projects_to_two_dims() {
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 18)),
                cond(ConditionTypes.LT, "amount", Map.of(ConditionParams.THRESHOLD, 1000))),
                null, null);

        Optional<RuleCube> cube = CubeProjector.project(booleanRule(ast));

        assertThat(cube).isPresent();
        assertThat(cube.get().dims()).containsOnlyKeys("age@METRIC", "amount@METRIC");
        assertThat(cube.get().isIncoherent()).isFalse();
    }

    @Test
    void single_condition_root_projects() {
        Optional<RuleCube> cube = CubeProjector.project(
                booleanRule(cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 18))));

        assertThat(cube).isPresent();
        assertThat(cube.get().dims()).containsOnlyKeys("age@METRIC");
    }

    @Test
    void two_contradicting_conditions_on_same_metric_meet_to_empty() {
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 30)),
                cond(ConditionTypes.LT, "age", Map.of(ConditionParams.THRESHOLD, 10))),
                null, null);

        Optional<RuleCube> cube = CubeProjector.project(booleanRule(ast));

        // 同维度两条件 meet → 空集 → 矛盾
        assertThat(cube).isPresent();
        assertThat(cube.get().dims()).containsOnlyKeys("age@METRIC");
        assertThat(cube.get().isIncoherent()).isTrue();
    }

    @Test
    void two_compatible_conditions_on_same_metric_are_coherent() {
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 10)),
                cond(ConditionTypes.LT, "age", Map.of(ConditionParams.THRESHOLD, 100))),
                null, null);

        Optional<RuleCube> cube = CubeProjector.project(booleanRule(ast));

        // (10,100) 非空 → 不矛盾
        assertThat(cube).isPresent();
        assertThat(cube.get().isIncoherent()).isFalse();
    }

    @Test
    void unknown_space_condition_still_projects_and_is_not_incoherent() {
        // MATCHES 降级为 unknown，但不影响投影
        Optional<RuleCube> cube = CubeProjector.project(
                booleanRule(cond(ConditionTypes.MATCHES, "name", Map.of(ConditionParams.REGEX, "^A.*"))));

        assertThat(cube).isPresent();
        assertThat(cube.get().dim("name@METRIC").isUnknown()).isTrue();
        assertThat(cube.get().isIncoherent()).isFalse();
    }

    @Test
    void or_root_is_unprojectable() {
        AstNode ast = new OrNode(List.of(
                cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 18)),
                cond(ConditionTypes.LT, "amount", Map.of(ConditionParams.THRESHOLD, 1000))),
                null, null);

        assertThat(CubeProjector.project(booleanRule(ast))).isEmpty();
    }

    @Test
    void nested_and_is_unprojectable() {
        AstNode inner = new AndNode(List.of(
                cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 18))), null, null);
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.LT, "amount", Map.of(ConditionParams.THRESHOLD, 1000)),
                inner), null, null);

        // 子节点含非叶子（嵌套 AND）→ 不可投影
        assertThat(CubeProjector.project(booleanRule(ast))).isEmpty();
    }

    @Test
    void non_ast_boolean_kind_is_unprojectable() {
        AstNode ast = cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 18));
        assertThat(CubeProjector.project(rule(ast, RuleKind.DECISION_TABLE.tag()))).isEmpty();
    }
}
