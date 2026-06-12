package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluationTest {

    private EvalContext ctx(Map<String, MetricValue> metrics) {
        return new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP),
                new Subject("u1", SubjectType.USER, Map.of()), metrics, Instant.now());
    }

    private ConditionNode gt(String metric, int threshold) {
        return new ConditionNode("GT", metric, null, Map.of("threshold", threshold), null, "LONG");
    }

    @Test
    void errorMetric_yieldsError() {
        ConditionOutcome out = ConditionEvaluation.evaluate(gt("balance", 100),
                ctx(Map.of("balance", MetricValue.error("METRIC_FETCH_FAIL"))),
                KernelEvaluators.defaults());
        assertThat(out.isError()).isTrue();
        assertThat(out.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }

    @Test
    void providerOpenErrorCode_passesThroughVerbatim() {
        // provider 开放码（非 EvalErrorCode 枚举值）应原样穿透到 ConditionOutcome.errorCode
        ConditionOutcome out = ConditionEvaluation.evaluate(gt("balance", 100),
                ctx(Map.of("balance", MetricValue.error("METRIC_SOURCE_EVAL_ERROR"))),
                KernelEvaluators.defaults());
        assertThat(out.isError()).isTrue();
        assertThat(out.errorCode()).isEqualTo("METRIC_SOURCE_EVAL_ERROR");
    }

    @Test
    void satisfied() {
        ConditionOutcome out = ConditionEvaluation.evaluate(gt("balance", 100),
                ctx(Map.of("balance", new MetricValue(200L, "LONG", "FETCHED"))),
                KernelEvaluators.defaults());
        assertThat(out.satisfied()).isTrue();
    }

    @Test
    void notSatisfied() {
        ConditionOutcome out = ConditionEvaluation.evaluate(gt("balance", 100),
                ctx(Map.of("balance", new MetricValue(50L, "LONG", "FETCHED"))),
                KernelEvaluators.defaults());
        assertThat(out.status()).isEqualTo(ConditionOutcome.Status.NOT_SATISFIED);
    }

    @Test
    void missingEvaluator_yieldsNoEvaluatorError() {
        ConditionOutcome out = ConditionEvaluation.evaluate(
                new ConditionNode("UNKNOWN_OP", "x", null, Map.of(), null, "LONG"),
                ctx(Map.of("x", new MetricValue(1L, "LONG", "FETCHED"))), Map.of());
        assertThat(out.isError()).isTrue();
        assertThat(out.errorCode()).isEqualTo("NO_EVALUATOR");
    }
}
