package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTreeExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTreeExecutorTest {

    private static final String GT = "GT";
    private static final String EQ = "EQ";

    private final ConditionEvaluator alwaysTrue  = (n, c) -> true;
    private final ConditionEvaluator alwaysFalse = (n, c) -> false;

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene", "EVT", "u1",
                "e1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private RuleVersionSnapshot snapshot(AstNode ast, String... decisionCodes) {
        List<RuleVersionSnapshot.DecisionBinding> bindings = java.util.Arrays.stream(decisionCodes)
                .map(code -> new RuleVersionSnapshot.DecisionBinding(code, 10))
                .toList();
        return new RuleVersionSnapshot(1L, "scene", "t1", ast,
                List.of(), bindings, List.of(), "DECISION_TREE");
    }

    private DecisionTreeExecutor executor(ConditionEvaluator... evals) {
        Map<String, ConditionEvaluator> map = new java.util.HashMap<>();
        map.put(GT, evals.length > 0 ? evals[0] : alwaysTrue);
        if (evals.length > 1) map.put(EQ, evals[1]);
        return new DecisionTreeExecutor(map);
    }

    @Test
    void singleIf_conditionTrue_returnsThenLeaf() {
        ConditionNode cond = new ConditionNode(GT, "amount", null, Map.of(), 0.0);
        DecisionLeafNode then = new DecisionLeafNode("BLOCK", "HIGH_RISK");
        IfNode tree = new IfNode(cond, then, null);

        EvalResult result = executor(alwaysTrue).execute(snapshot(tree, "BLOCK"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.finalDecision().code()).isEqualTo("BLOCK");
        assertThat(result.category()).isEqualTo("HIGH_RISK");
        assertThat(result.decision()).isNull();
    }

    @Test
    void singleIf_conditionFalse_noElse_returnsMiss() {
        ConditionNode cond = new ConditionNode(GT, "amount", null, Map.of(), 0.0);
        DecisionLeafNode then = new DecisionLeafNode("BLOCK", "HIGH_RISK");
        IfNode tree = new IfNode(cond, then, null);

        EvalResult result = executor(alwaysFalse).execute(snapshot(tree, "BLOCK"), ctx());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void singleIf_conditionFalse_withElse_returnsElseLeaf() {
        ConditionNode cond = new ConditionNode(GT, "amount", null, Map.of(), 0.0);
        DecisionLeafNode then = new DecisionLeafNode("BLOCK", "HIGH_RISK");
        DecisionLeafNode elseLeaf = new DecisionLeafNode("PASS", "LOW_RISK");
        IfNode tree = new IfNode(cond, then, elseLeaf);

        EvalResult result = executor(alwaysFalse).execute(snapshot(tree, "BLOCK", "PASS"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.finalDecision().code()).isEqualTo("PASS");
        assertThat(result.category()).isEqualTo("LOW_RISK");
    }

    @Test
    void nestedIf_innerBranchHit() {
        // if(GT) { if(EQ) { BLOCK } else { REVIEW } } else { PASS }
        ConditionNode outerCond = new ConditionNode(GT, "amount", null, Map.of(), 0.0);
        ConditionNode innerCond = new ConditionNode(EQ, "country", null, Map.of(), 0.0);
        DecisionLeafNode blockLeaf  = new DecisionLeafNode("BLOCK", "FRAUD");
        DecisionLeafNode reviewLeaf = new DecisionLeafNode("REVIEW", "SUSPECT");
        DecisionLeafNode passLeaf   = new DecisionLeafNode("PASS", "SAFE");

        IfNode inner = new IfNode(innerCond, blockLeaf, reviewLeaf);
        IfNode tree  = new IfNode(outerCond, inner, passLeaf);

        // GT=true, EQ=true → BLOCK
        Map<String, ConditionEvaluator> evals = Map.of(GT, alwaysTrue, EQ, alwaysTrue);
        EvalResult result = new DecisionTreeExecutor(evals)
                .execute(snapshot(tree, "BLOCK", "REVIEW", "PASS"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.finalDecision().code()).isEqualTo("BLOCK");
        assertThat(result.category()).isEqualTo("FRAUD");
    }

    @Test
    void nestedIf_outerFalse_returnsOuterElse() {
        ConditionNode outerCond = new ConditionNode(GT, "amount", null, Map.of(), 0.0);
        ConditionNode innerCond = new ConditionNode(EQ, "country", null, Map.of(), 0.0);
        IfNode inner   = new IfNode(innerCond, new DecisionLeafNode("BLOCK", null), null);
        DecisionLeafNode passLeaf = new DecisionLeafNode("PASS", "SAFE");
        IfNode tree = new IfNode(outerCond, inner, passLeaf);

        Map<String, ConditionEvaluator> evals = Map.of(GT, alwaysFalse, EQ, alwaysTrue);
        EvalResult result = new DecisionTreeExecutor(evals)
                .execute(snapshot(tree, "BLOCK", "PASS"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.finalDecision().code()).isEqualTo("PASS");
    }

    @Test
    void hitLeaf_decisionCarriesLeafCategory() {
        // 单层决策树:if(GT) then leaf(decisionCode=REVIEW, category=中危),命中后 category 焊到 Decision
        ConditionNode cond = new ConditionNode(GT, "flag", null, Map.of(), 0.0);
        DecisionLeafNode then = new DecisionLeafNode("REVIEW", "中危");
        IfNode tree = new IfNode(cond, then, null);

        EvalResult result = executor(alwaysTrue).execute(snapshot(tree, "REVIEW"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.finalDecision().code()).isEqualTo("REVIEW");
        assertThat(result.finalDecision().category()).isEqualTo("中危");
    }

    @Test
    void wrongAstType_returnsErrorCode() {
        AndNode wrongAst = new AndNode(List.of(), null, null);
        EvalResult result = executor(alwaysTrue)
                .execute(snapshot(wrongAst, "BLOCK"), ctx());

        assertThat(result.ruleHit()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DECISION_TREE_AST_TYPE_MISMATCH");
    }
}
