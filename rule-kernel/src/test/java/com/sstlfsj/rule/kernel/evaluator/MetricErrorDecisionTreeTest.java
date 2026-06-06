package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTreeExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricErrorDecisionTreeTest {

    @Test
    void errorCondition_wholeRuleError_doesNotTakeElse() {
        ConditionNode cond = new ConditionNode("GT", "balance", null, Map.of("threshold", 100), null, "LONG");
        IfNode root = new IfNode(cond,
                new DecisionLeafNode("REVIEW", "high"),
                new DecisionLeafNode("PASS", "low"));   // else 是"放行"，绝不能因取数失败静默命中
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(root)
                .kind("DECISION_TREE").build();
        EvalContext ctx = new EvalContext("1",
                new RuleEvent("1", "PAY", "transfer", "u1", "e1", Instant.now(), Map.of(), Map.of()),
                new Subject("u1", SubjectType.USER, Map.of()),
                Map.of("balance", MetricValue.error("METRIC_FETCH_FAIL")), Instant.now());

        EvalResult r = new DecisionTreeExecutor(KernelEvaluators.defaults()).execute(snap, ctx);

        assertThat(r.ruleHit()).isFalse();           // 没有静默命中 PASS
        assertThat(r.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }
}
