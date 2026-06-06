package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.DecisionTableExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTableExecutorTest {

    private final ConditionEvaluator alwaysTrue  = (n, c) -> true;
    private final ConditionEvaluator alwaysFalse = (n, c) -> false;

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene", "EVT", "u1",
                "e1", Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private RuleVersionSnapshot snapshot(DecisionTableNode ast, String... codes) {
        List<RuleVersionSnapshot.DecisionBinding> bindings = java.util.Arrays.stream(codes)
                .map(c -> new RuleVersionSnapshot.DecisionBinding(c, 10))
                .toList();
        return new RuleVersionSnapshot(1L, "scene", "t1", ast,
                List.of(), bindings, List.of(), "DECISION_TABLE");
    }

    private DecisionTableNode table(List<DecisionTableNode.Column> cols,
                                    List<DecisionTableNode.Row> rows) {
        return new DecisionTableNode(cols, rows);
    }

    @Test
    void firstRowMatches_returnsHit() {
        var col = new DecisionTableNode.Column("amount", "GT");
        var row1 = new DecisionTableNode.Row(List.of(1000), "BLOCK");
        var row2 = new DecisionTableNode.Row(List.of(500),  "REVIEW");
        DecisionTableNode ast = table(List.of(col), List.of(row1, row2));

        EvalResult result = new DecisionTableExecutor(Map.of("GT", alwaysTrue))
                .execute(snapshot(ast, "BLOCK", "REVIEW"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.finalDecision().code()).isEqualTo("BLOCK");
        assertThat(result.decision()).isEqualTo("BLOCK");
        assertThat(result.category()).isNull();
    }

    @Test
    void columnDataType_isPassedToSynthesizedConditionNode() {
        // B22：列冻结的 dataType 应传入求值期合成的 ConditionNode
        var col = new DecisionTableNode.Column("amount", "GT", "LONG");
        var row = new DecisionTableNode.Row(List.of(1000), "BLOCK");
        DecisionTableNode ast = table(List.of(col), List.of(row));

        String[] seen = new String[1];
        ConditionEvaluator capturing = (n, c) -> { seen[0] = n.dataType(); return true; };

        EvalResult result = new DecisionTableExecutor(Map.of("GT", capturing))
                .execute(snapshot(ast, "BLOCK"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(seen[0]).isEqualTo("LONG");
    }

    @Test
    void firstRowFails_secondRowMatches() {
        var col = new DecisionTableNode.Column("amount", "GT");
        var row1 = new DecisionTableNode.Row(List.of(1000), "BLOCK");
        var row2 = new DecisionTableNode.Row(List.of(500),  "REVIEW");

        // 第一行 GT 失败，第二行 GT 成功
        ConditionEvaluator firstFalseRestTrue = new ConditionEvaluator() {
            int callCount = 0;
            @Override public boolean evaluate(com.sstlfsj.rule.kernel.api.model.ast.ConditionNode node, EvalContext ctx) {
                return callCount++ > 0;
            }
        };
        DecisionTableNode ast = table(List.of(col), List.of(row1, row2));

        EvalResult result = new DecisionTableExecutor(Map.of("GT", firstFalseRestTrue))
                .execute(snapshot(ast, "BLOCK", "REVIEW"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.finalDecision().code()).isEqualTo("REVIEW");
    }

    @Test
    void wildcardColumn_null_alwaysMatches() {
        var col = new DecisionTableNode.Column("amount", "GT");
        // null 表示通配，不调用 evaluator，直接认为满足
        var row = new DecisionTableNode.Row(java.util.Arrays.asList((Object) null), "PASS");
        DecisionTableNode ast = table(List.of(col), List.of(row));

        // 即使没有注册 GT evaluator，null 通配应直接命中
        EvalResult result = new DecisionTableExecutor(Map.of())
                .execute(snapshot(ast, "PASS"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.finalDecision().code()).isEqualTo("PASS");
    }

    @Test
    void noRowMatches_returnsMiss() {
        var col = new DecisionTableNode.Column("amount", "GT");
        var row = new DecisionTableNode.Row(List.of(1000), "BLOCK");
        DecisionTableNode ast = table(List.of(col), List.of(row));

        EvalResult result = new DecisionTableExecutor(Map.of("GT", alwaysFalse))
                .execute(snapshot(ast, "BLOCK"), ctx());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void emptyTable_returnsMiss() {
        DecisionTableNode ast = table(List.of(), List.of());

        EvalResult result = new DecisionTableExecutor(Map.of())
                .execute(snapshot(ast), ctx());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void multipleColumns_allMustMatch() {
        var col1 = new DecisionTableNode.Column("amount", "GT");
        var col2 = new DecisionTableNode.Column("country", "EQ");
        var row = new DecisionTableNode.Row(List.of(1000, "CN"), "BLOCK");
        DecisionTableNode ast = table(List.of(col1, col2), List.of(row));

        // GT=true, EQ=false → 不命中
        EvalResult miss = new DecisionTableExecutor(Map.of("GT", alwaysTrue, "EQ", alwaysFalse))
                .execute(snapshot(ast, "BLOCK"), ctx());
        assertThat(miss.ruleHit()).isFalse();

        // GT=true, EQ=true → 命中
        EvalResult hit = new DecisionTableExecutor(Map.of("GT", alwaysTrue, "EQ", alwaysTrue))
                .execute(snapshot(ast, "BLOCK"), ctx());
        assertThat(hit.ruleHit()).isTrue();
        assertThat(hit.finalDecision().code()).isEqualTo("BLOCK");
    }

    @Test
    void wrongAstType_returnsErrorCode() {
        AndNode wrongAst = new AndNode(List.of(), null, null);
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", wrongAst,
                List.of(), List.of(), List.of(), "DECISION_TABLE");

        EvalResult result = new DecisionTableExecutor(Map.of()).execute(snap, ctx());

        assertThat(result.ruleHit()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DECISION_TABLE_AST_TYPE_MISMATCH");
    }
}
