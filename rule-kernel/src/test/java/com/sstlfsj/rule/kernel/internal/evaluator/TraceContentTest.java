package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
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
}
