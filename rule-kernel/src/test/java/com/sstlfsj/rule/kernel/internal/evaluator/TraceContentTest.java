package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.NodeType;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证叶子 trace 携带实际值/来源（增量1），以及 Scorecard 顶层 ScorecardRoot 根节点。 */
class TraceContentTest {

    /** GTE 叶子算子：metric 值 >= 0 时命中。 */
    private final ConditionEvaluator gte = (n, c) ->
            ((Number) c.getMetric(n.metricCode()).value()).longValue() >= 0;

    private EvalContext ctxWith(Map<String, MetricValue> metrics) {
        RuleEvent event = new RuleEvent("1", "PAY", "transfer", "u1", "e1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
        return new EvalContext("1", event, new Subject("u1", SubjectType.USER, Map.of()),
                metrics, Instant.now());
    }

    @Test
    void interpretedLeafTrace_carriesActualValueAndSource() {
        ConditionNode node = new ConditionNode("GTE", "score", "score>=0",
                Map.of("threshold", 0), null, "LONG");
        InterpretedExecutor exec = new InterpretedExecutor(Map.of("GTE", gte));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(7L).tenantId("1").sceneCode("PAY").conditionAst(node).build();
        EvalContext ctx = ctxWith(Map.of("score", new MetricValue(100L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(snap, ctx);

        NodeTrace leaf = r.nodeTrace().getFirst();
        assertThat(leaf.nodeType()).isEqualTo("ConditionNode");
        assertThat(leaf.result()).isTrue();
        assertThat(leaf.actualValue()).isEqualTo(100L);
        assertThat(leaf.valueSource()).isEqualTo("PROVIDED");
        // 叶子自携带期望值（params）与可读标签（displayLabel），随 trace 落库（增量3）
        assertThat(leaf.expectedValue()).isEqualTo(Map.of("threshold", 0));
        assertThat(leaf.displayLabel()).isEqualTo("score>=0");
    }

    @Test
    void scorecardTrace_wrapsFactorsInScorecardRoot_withLeafValues() {
        ConditionNode f1 = new ConditionNode("GTE", "score", "score>=0",
                Map.of("threshold", 0), 50.0, "LONG");
        ScorecardRootNode root = new ScorecardRootNode(List.of(f1), 50.0);
        ScorecardExecutor exec = new ScorecardExecutor(Map.of("GTE", gte));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(9L).tenantId("1").sceneCode("PAY").conditionAst(root)
                .kind("SCORECARD").build();
        EvalContext ctx = ctxWith(Map.of("score", new MetricValue(100L, "LONG", "FETCHED")));

        EvalResult r = exec.execute(snap, ctx);

        assertThat(r.ruleHit()).isTrue();
        NodeTrace rootTrace = r.nodeTrace().getFirst();
        assertThat(rootTrace.nodeType()).isEqualTo("ScorecardRoot");
        assertThat(rootTrace.result()).isTrue();
        assertThat(rootTrace.children()).hasSize(1);
        NodeTrace factor = rootTrace.children().getFirst();
        assertThat(factor.nodeType()).isEqualTo("ConditionNode");
        assertThat(factor.actualValue()).isEqualTo(100L);
        assertThat(factor.valueSource()).isEqualTo("FETCHED");
    }

    @Test
    void containerCarriesRuleCodeAndVersion() {
        NodeTrace t = NodeTrace.container(NodeType.AND, true, java.util.List.of(), 100L, "large-trade", 3L);
        assertThat(t.ruleCode()).isEqualTo("large-trade");
        assertThat(t.ruleVersion()).isEqualTo(3L);
        assertThat(t.ruleVersionId()).isEqualTo(100L);
    }

    @Test
    void interpretedExecutor_threadsSnapshotCodeAndVersion_intoTopTrace() {
        ConditionNode node = new ConditionNode("GTE", "score", "score>=0",
                Map.of("threshold", 0), null, "LONG");
        InterpretedExecutor exec = new InterpretedExecutor(Map.of("GTE", gte));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(7L).tenantId("1").sceneCode("PAY").conditionAst(node)
                .code("r1").version(2L)
                .addDecisionBinding("APPROVE", 10)
                .build();
        EvalContext ctx = ctxWith(Map.of("score", new MetricValue(100L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(snap, ctx);

        // 顶层 trace 携带 snapshot 的 code/version（与 ruleVersionId 同作用域）
        NodeTrace top = r.nodeTrace().get(0);
        assertThat(top.ruleCode()).isEqualTo("r1");
        assertThat(top.ruleVersion()).isEqualTo(2L);
    }

    @Test
    void decisionTreeExecutor_threadsSnapshotCodeAndVersion_intoTraceAndDecision() {
        ConditionNode cond = new ConditionNode("GTE", "score", "score>=0",
                Map.of("threshold", 0), null, "LONG");
        com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode thenLeaf =
                new com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode("APPROVE", "APPROVE");
        com.sstlfsj.rule.kernel.api.model.ast.IfNode root =
                new com.sstlfsj.rule.kernel.api.model.ast.IfNode(cond, thenLeaf, null);
        DecisionTreeExecutor exec = new DecisionTreeExecutor(Map.of("GTE", gte));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(7L).tenantId("1").sceneCode("PAY").conditionAst(root)
                .code("r1").version(2L)
                .addDecisionBinding("APPROVE", 10)
                .build();
        EvalContext ctx = ctxWith(Map.of("score", new MetricValue(100L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(snap, ctx);

        // 顶层 trace 携带 snapshot 的 code/version
        NodeTrace top = r.nodeTrace().get(0);
        assertThat(top.ruleCode()).isEqualTo("r1");
        assertThat(top.ruleVersion()).isEqualTo(2L);
        // Decision 携带 snapshot 的 code/version
        assertThat(r.finalDecision().fromRuleCode()).isEqualTo("r1");
        assertThat(r.finalDecision().fromRuleVersion()).isEqualTo(2L);
    }

    @Test
    void scorecardExecutor_threadsSnapshotCodeAndVersion_intoRootAndFactors() {
        ConditionNode f1 = new ConditionNode("GTE", "score", "score>=0",
                Map.of("threshold", 0), 50.0, "LONG");
        ScorecardRootNode root = new ScorecardRootNode(List.of(f1), 50.0);
        ScorecardExecutor exec = new ScorecardExecutor(Map.of("GTE", gte));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(9L).tenantId("1").sceneCode("PAY").conditionAst(root)
                .code("r1").version(2L).kind("SCORECARD").build();
        EvalContext ctx = ctxWith(Map.of("score", new MetricValue(100L, "LONG", "FETCHED")));

        EvalResult r = exec.execute(snap, ctx);

        // 根节点与各因子叶子均携带 snapshot 的 code/version
        NodeTrace rootTrace = r.nodeTrace().get(0);
        assertThat(rootTrace.ruleCode()).isEqualTo("r1");
        assertThat(rootTrace.ruleVersion()).isEqualTo(2L);
        assertThat(rootTrace.children().get(0).ruleCode()).isEqualTo("r1");
        assertThat(rootTrace.children().get(0).ruleVersion()).isEqualTo(2L);
    }
}
