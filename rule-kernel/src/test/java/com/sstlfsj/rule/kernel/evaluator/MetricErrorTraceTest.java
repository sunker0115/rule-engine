package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricErrorTraceTest {

    @Test
    void errorMetric_nodeNotSatisfied_andErrorCodeOnTrace() {
        ConditionNode node = new ConditionNode("GT", "balance", null, Map.of("threshold", 100), null, "LONG");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(node)
                .addMetricDependency("balance", 1).build();
        EvalContext ctx = new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP),
                new Subject("u1", SubjectType.USER, Map.of()),
                Map.of("balance", MetricValue.error("METRIC_FETCH_FAIL")),
                Instant.now());

        EvalResult r = new InterpretedExecutor(KernelEvaluators.defaults()).execute(snap, ctx);

        assertThat(r.ruleHit()).isFalse();
        NodeTrace t = r.nodeTrace().get(0);
        assertThat(t.result()).isFalse();
        assertThat(t.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }
}
