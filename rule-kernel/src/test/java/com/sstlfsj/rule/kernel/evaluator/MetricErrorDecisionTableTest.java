package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTableExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricErrorDecisionTableTest {

    @Test
    void errorColumn_wholeTableError_doesNotFallThrough() {
        DecisionTableNode.Column col = new DecisionTableNode.Column("balance", "GT");
        DecisionTableNode.Row row = new DecisionTableNode.Row(List.of(100), "REVIEW");
        DecisionTableNode table = new DecisionTableNode(List.of(col), List.of(row));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(table)
                .kind("DECISION_TABLE").build();
        EvalContext ctx = new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP),
                new Subject("u1", SubjectType.USER, Map.of()),
                Map.of("balance", MetricValue.error("METRIC_FETCH_FAIL")), Instant.now());

        EvalResult r = new DecisionTableExecutor(KernelEvaluators.defaults()).execute(snap, ctx);

        assertThat(r.ruleHit()).isFalse();
        assertThat(r.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }
}
