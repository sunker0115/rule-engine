package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.IncoherenceFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
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

import static org.assertj.core.api.Assertions.assertThat;

/** IncoherenceDetector：矛盾规则检测的端到端行为测试。 */
class IncoherenceDetectorTest {

    private static ConditionNode cond(String type, String metric, Map<String, Object> params) {
        return new ConditionNode(type, metric, null, params, 0.0, null, ValueRef.METRIC);
    }

    private static AnalyzableRule rule(String code, AstNode ast, String kind) {
        return new AnalyzableRule(code, 1L, ast, List.of(), kind);
    }

    private static AnalyzableRule booleanRule(String code, AstNode ast) {
        return rule(code, ast, RuleKind.AST_BOOLEAN.tag());
    }

    @Test
    void contradictory_rule_yields_one_error_finding() {
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 30)),
                cond(ConditionTypes.LT, "age", Map.of(ConditionParams.THRESHOLD, 10))),
                null, null);

        List<IncoherenceFinding> findings = IncoherenceDetector.detect(List.of(booleanRule("R-bad", ast)));

        assertThat(findings).hasSize(1);
        IncoherenceFinding f = findings.getFirst();
        assertThat(f.ruleCode()).isEqualTo("R-bad");
        assertThat(f.severity()).isEqualTo(Severity.ERROR);
        assertThat(f.reason()).contains("age@METRIC");
    }

    @Test
    void coherent_rule_yields_no_finding() {
        AstNode ast = new AndNode(List.of(
                cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 10)),
                cond(ConditionTypes.LT, "age", Map.of(ConditionParams.THRESHOLD, 100))),
                null, null);

        assertThat(IncoherenceDetector.detect(List.of(booleanRule("R-ok", ast)))).isEmpty();
    }

    @Test
    void unprojectable_rule_is_skipped() {
        AstNode ast = new OrNode(List.of(
                cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 30)),
                cond(ConditionTypes.LT, "age", Map.of(ConditionParams.THRESHOLD, 10))),
                null, null);

        // OR 根不可投影 → 跳过，不报矛盾（即便条件看似矛盾，OR 语义下不成立）
        assertThat(IncoherenceDetector.detect(List.of(booleanRule("R-or", ast)))).isEmpty();
    }

    @Test
    void unknown_space_rule_is_not_flagged() {
        AstNode ast = cond(ConditionTypes.MATCHES, "name", Map.of(ConditionParams.REGEX, "^A.*"));
        assertThat(IncoherenceDetector.detect(List.of(booleanRule("R-unknown", ast)))).isEmpty();
    }

    @Test
    void detects_only_contradictory_rules_in_mixed_set() {
        AstNode bad = new AndNode(List.of(
                cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 30)),
                cond(ConditionTypes.LT, "age", Map.of(ConditionParams.THRESHOLD, 10))),
                null, null);
        AstNode good = cond(ConditionTypes.GT, "age", Map.of(ConditionParams.THRESHOLD, 18));

        List<IncoherenceFinding> findings = IncoherenceDetector.detect(List.of(
                booleanRule("R-good", good),
                booleanRule("R-bad", bad)));

        assertThat(findings).extracting(IncoherenceFinding::ruleCode).containsExactly("R-bad");
    }
}
