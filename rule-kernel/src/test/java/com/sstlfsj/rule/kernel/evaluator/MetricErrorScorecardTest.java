package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricErrorScorecardTest {

    @Test
    void anyErrorCondition_wholeCardError_noScore() {
        ConditionNode c1 = new ConditionNode("GT", "good", null, Map.of("threshold", 1), 10.0, "LONG");
        ConditionNode c2 = new ConditionNode("GT", "broken", null, Map.of("threshold", 1), 10.0, "LONG");
        ScorecardRootNode root = new ScorecardRootNode(List.of(c1, c2), 5.0, java.util.List.of());
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(root)
                .kind("SCORECARD").build();
        EvalContext ctx = new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP),
                new Subject("u1", SubjectType.USER, Map.of()),
                Map.of("good", new MetricValue(100L, "LONG", "FETCHED"),
                       "broken", MetricValue.error("METRIC_FETCH_FAIL")),
                Instant.now());

        EvalResult r = new ScorecardExecutor(KernelEvaluators.defaults()).execute(snap, ctx);

        assertThat(r.ruleHit()).isFalse();
        assertThat(r.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
        assertThat(r.score()).isNull();
    }
}
