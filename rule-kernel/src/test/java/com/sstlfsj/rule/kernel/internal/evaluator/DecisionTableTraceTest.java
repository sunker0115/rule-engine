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
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 DecisionTableExecutor 构建全保真 NodeTrace（增量2），并遵循 TraceScope.COLLECT 零分配契约。 */
class DecisionTableTraceTest {

    /** EQ 叶子算子：metric 值等于 threshold（数值相等）时命中。 */
    private final ConditionEvaluator eq = (n, c) -> {
        long actual = ((Number) c.getMetric(n.metricCode()).value()).longValue();
        long threshold = ((Number) n.params().get("threshold")).longValue();
        return actual == threshold;
    };

    private EvalContext ctxWith(Map<String, MetricValue> metrics) {
        RuleEvent event = new RuleEvent("1", "PAY", "transfer", "u1", "e1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
        return new EvalContext("1", event, new Subject("u1", SubjectType.USER, Map.of()),
                metrics, Instant.now());
    }

    private RuleVersionSnapshot tableSnap(DecisionTableNode table) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(11L).tenantId("1").sceneCode("PAY").conditionAst(table)
                .kind("DECISION_TABLE")
                .addDecisionBinding("ROW1", 10)
                .addDecisionBinding("ROW2", 5)
                .build();
    }

    /** 单列 level：row1 要求 level=1，row2 要求 level=2。 */
    private DecisionTableNode twoRowTable() {
        DecisionTableNode.Column col = new DecisionTableNode.Column("level", "EQ", "LONG");
        DecisionTableNode.Row row1 = new DecisionTableNode.Row(List.of(1), "ROW1");
        DecisionTableNode.Row row2 = new DecisionTableNode.Row(List.of(2), "ROW2");
        return new DecisionTableNode(List.of(col), List.of(row1, row2));
    }

    @Test
    void tableTrace_secondRowMatches_recordsBothRows_withColumnChildren() {
        DecisionTableExecutor exec = new DecisionTableExecutor(Map.of("EQ", eq));
        EvalContext ctx = ctxWith(Map.of("level", new MetricValue(2L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(tableSnap(twoRowTable()), ctx);

        // 命中行为不变：第二行胜出
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("ROW2");

        // 测试了两行 → 两个 DecisionTableRow
        assertThat(r.nodeTrace()).hasSize(2);
        NodeTrace r1 = r.nodeTrace().get(0);
        assertThat(r1.nodeType()).isEqualTo("DecisionTableRow");
        assertThat(r1.result()).isFalse();
        assertThat(r1.ruleVersionId()).isEqualTo(11L);
        assertThat(r1.children()).hasSize(1);
        NodeTrace r1col = r1.children().getFirst();
        assertThat(r1col.nodeType()).isEqualTo("ConditionNode");
        assertThat(r1col.conditionType()).isEqualTo("EQ");
        assertThat(r1col.metricCode()).isEqualTo("level");
        assertThat(r1col.result()).isFalse();
        assertThat(r1col.actualValue()).isEqualTo(2L);
        assertThat(r1col.valueSource()).isEqualTo("PROVIDED");

        NodeTrace r2 = r.nodeTrace().get(1);
        assertThat(r2.nodeType()).isEqualTo("DecisionTableRow");
        assertThat(r2.result()).isTrue();
        assertThat(r2.children().getFirst().result()).isTrue();
    }

    @Test
    void tableTrace_firstRowMatches_stopsAfterFirst() {
        DecisionTableExecutor exec = new DecisionTableExecutor(Map.of("EQ", eq));
        EvalContext ctx = ctxWith(Map.of("level", new MetricValue(1L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(tableSnap(twoRowTable()), ctx);

        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("ROW1");
        // FIRST_HIT：第一行命中即停，只记录一行
        assertThat(r.nodeTrace()).hasSize(1);
        assertThat(r.nodeTrace().getFirst().result()).isTrue();
    }

    @Test
    void tableTrace_wildcardColumn_skippedFromColumnTraces() {
        // 两列 [level, region]；row1 仅约束 level（region 通配 null）
        DecisionTableNode.Column c1 = new DecisionTableNode.Column("level", "EQ", "LONG");
        DecisionTableNode.Column c2 = new DecisionTableNode.Column("region", "EQ", "LONG");
        DecisionTableNode.Row row = new DecisionTableNode.Row(Arrays.asList(1, null), "ROW1");
        DecisionTableNode table = new DecisionTableNode(List.of(c1, c2), List.of(row));
        DecisionTableExecutor exec = new DecisionTableExecutor(Map.of("EQ", eq));
        EvalContext ctx = ctxWith(Map.of("level", new MetricValue(1L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(tableSnap(table), ctx);

        assertThat(r.ruleHit()).isTrue();
        // 通配列不产生 column trace，仅 level 一列
        assertThat(r.nodeTrace().getFirst().children()).hasSize(1);
        assertThat(r.nodeTrace().getFirst().children().getFirst().metricCode()).isEqualTo("level");
    }

    @Test
    void tableTrace_collectDisabled_isEmpty_butDecisionUnchanged() throws Exception {
        DecisionTableExecutor exec = new DecisionTableExecutor(Map.of("EQ", eq));
        RuleVersionSnapshot snap = tableSnap(twoRowTable());
        EvalContext ctx = ctxWith(Map.of("level", new MetricValue(2L, "LONG", "PROVIDED")));

        EvalResult off = ScopedValue.where(TraceScope.COLLECT, false).call(() -> exec.execute(snap, ctx));
        EvalResult on = exec.execute(snap, ctx);

        assertThat(off.nodeTrace()).isEmpty();
        assertThat(off.ruleHit()).isEqualTo(on.ruleHit());
        assertThat(off.finalDecision().code()).isEqualTo(on.finalDecision().code());
    }
}
