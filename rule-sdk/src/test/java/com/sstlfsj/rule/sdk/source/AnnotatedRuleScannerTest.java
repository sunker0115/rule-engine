package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AnnotatedRuleScannerTest {

    @RuleDef(code = "even", sceneCode = "demo",
            decisions = @DecisionBinding(code = "EVEN", priority = 1))
    static class EvenRule {
        @Condition
        public boolean isEven(@Fact("number") Integer n, @Metric("total") Integer total) {
            return n % 2 == 0;
        }
    }

    @RuleDef(code = "bad", sceneCode = "demo")
    static class NoConditionRule {}

    @Test
    void scan_buildsSnapshotEvaluatorAndMetricDependency() {
        AnnotatedRuleScanner.ScanResult r =
                new AnnotatedRuleScanner(new FactResolver(), "t1").scan(List.of(new EvenRule()));

        assertThat(r.snapshots()).hasSize(1);
        RuleVersionSnapshot snap = r.snapshots().get(0);
        assertThat(snap.sceneCode()).isEqualTo("demo");
        assertThat(snap.code()).isEqualTo("even");
        assertThat(snap.tenantId()).isEqualTo("t1");
        assertThat(snap.metricDependencies())
                .extracting(MetricDependency::metricCode).containsExactly("total");
        // 合成算子键 = conditionAst 叶子的 conditionType,且 evaluators 含同键
        String condType = ((com.sstlfsj.rule.kernel.api.model.ast.ConditionNode) ((com.sstlfsj.rule.kernel.api.model.AstBody) snap.body()).conditionAst()).conditionType();
        assertThat(r.evaluators()).containsKey(condType);
    }

    @Test
    void scan_missingCondition_throws() {
        assertThatThrownBy(() ->
                new AnnotatedRuleScanner(new FactResolver(), "t1").scan(List.of(new NoConditionRule())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Condition");
    }
}
