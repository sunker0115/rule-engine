package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScoreBand;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ScorecardExecutor 单测：验证权重累加、阈值判断、trace 生成。 */
class ScorecardExecutorTest {

    private static final String ALWAYS_TRUE  = "ALWAYS_TRUE";
    private static final String ALWAYS_FALSE = "ALWAYS_FALSE";

    private final ConditionEvaluator alwaysTrue  = (node, ctx) -> true;
    private final ConditionEvaluator alwaysFalse = (node, ctx) -> false;

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene1", "ORDER_PLACED", "u1",
                "evt-1", Instant.now(), Map.of(), null, com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private RuleVersionSnapshot snapshot(ScorecardRootNode root) {
        return new RuleVersionSnapshot(1L, "scene1", "t1", root, null, null, null, null);
    }

    @Test
    void allConditionsMet_scoreEqualsSum() {
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 30.0),
                new ConditionNode(ALWAYS_TRUE, "m2", null, Map.of(), 70.0)
        ), 80.0, java.util.List.of());
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.score()).isEqualTo(100.0);
    }

    @Test
    void partialConditionsMet_scoreBelow_threshold_miss() {
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE,  "m1", null, Map.of(), 30.0),
                new ConditionNode(ALWAYS_FALSE, "m2", null, Map.of(), 70.0)
        ), 80.0, java.util.List.of());
        EvalResult result = new ScorecardExecutor(
                Map.of(ALWAYS_TRUE, alwaysTrue, ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isFalse();
        assertThat(result.score()).isEqualTo(30.0);
    }

    @Test
    void scoreEqualsThreshold_isHit() {
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 50.0)
        ), 50.0, java.util.List.of());
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.score()).isEqualTo(50.0);
    }

    @Test
    void noConditions_scoreZero_miss() {
        ScorecardRootNode root = new ScorecardRootNode(List.of(), 1.0, java.util.List.of());
        EvalResult result = new ScorecardExecutor(Map.of())
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isFalse();
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void nodeTrace_containsAllConditions_noShortCircuit() {
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE,  "m1", null, Map.of(), 30.0),
                new ConditionNode(ALWAYS_FALSE, "m2", null, Map.of(), 20.0)
        ), 100.0, java.util.List.of());
        EvalResult result = new ScorecardExecutor(
                Map.of(ALWAYS_TRUE, alwaysTrue, ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(root), ctx());
        // 顶层是单一 ScorecardRoot，各因子在其 children 中（不短路，全量遍历）
        assertThat(result.nodeTrace()).hasSize(1);
        assertThat(result.nodeTrace().getFirst().nodeType()).isEqualTo("ScorecardRoot");
        assertThat(result.nodeTrace().getFirst().children()).hasSize(2);
    }

    @Test
    void nullWeight_conditionMet_doesNotAccumulateAndNoNpe() {
        // weight=null 时即使条件命中也不累加分数，且不抛 NPE（AST_BOOLEAN 场景兼容）
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), null)
        ), 0.0, java.util.List.of());
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void collectFalse_noTrace_sameScore() throws Exception {
        // collect=false 时跳过 NodeTrace 构建，score/hit 与 collect=true 完全一致
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE,  "m1", null, Map.of(), 30.0),
                new ConditionNode(ALWAYS_FALSE, "m2", null, Map.of(), 70.0)
        ), 80.0, java.util.List.of());
        ScorecardExecutor executor = new ScorecardExecutor(
                Map.of(ALWAYS_TRUE, alwaysTrue, ALWAYS_FALSE, alwaysFalse));
        RuleVersionSnapshot snap = snapshot(root);
        EvalContext c = ctx();

        EvalResult on  = ScopedValue.where(TraceScope.COLLECT, true).call(() -> executor.execute(snap, c));
        EvalResult off = ScopedValue.where(TraceScope.COLLECT, false).call(() -> executor.execute(snap, c));

        assertThat(off.nodeTrace()).isEmpty();
        assertThat(off.score()).isEqualTo(on.score());
        assertThat(off.ruleHit()).isEqualTo(on.ruleHit());
    }

    @Test
    void bandsHit_emitsDecisionWithCategory() {
        // score=70 落 [60,80) → REVIEW/MEDIUM
        ScoreBand low = new ScoreBand(0, 60, "REJECT", "HIGH");
        ScoreBand mid = new ScoreBand(60, 80, "REVIEW", "MEDIUM");
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 70.0)
        ), 0.0, List.of(low, mid));
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.score()).isEqualTo(70.0);
        assertThat(result.finalDecision().code()).isEqualTo("REVIEW");
        assertThat(result.category()).isEqualTo("MEDIUM");
        assertThat(result.hitDecisions()).hasSize(1);
    }

    @Test
    void bandsWithNamePriority_decisionContainsNameAndPriority() {
        // band 含回填的 name/priority → Decision 应直接从 band 读，不索引 decisionBindings
        ScoreBand rich = new ScoreBand(60, 100, "PASS", "LOW", "通过", 100);
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 70.0)
        ), 0.0, List.of(new ScoreBand(0, 60, "REJECT", "HIGH", "拒绝", 1), rich));
        // decisionBindings 为空（评分卡发布后不再注入）——执行器直接从 band 读
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                1L, "scene1", "t1", root, null, List.of(), null, null);
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snap, ctx());
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.score()).isEqualTo(70.0);
        assertThat(result.finalDecision().code()).isEqualTo("PASS");
        assertThat(result.finalDecision().name()).isEqualTo("通过");
        assertThat(result.finalDecision().priority()).isEqualTo(100);
        assertThat(result.category()).isEqualTo("LOW");
    }

    @Test
    void belowThreshold_notHit_withBands() {
        // score=10 < threshold 50 → 弃权，带 score 无 decision
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 10.0)
        ), 50.0, List.of(new ScoreBand(0, 100, "X", null)));
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isFalse();
        assertThat(result.score()).isEqualTo(10.0);
        assertThat(result.finalDecision()).isNull();
    }

    @Test
    void scoreInGap_hitsButNoBandDecision() {
        // bands 只覆盖 [0,60)，score=70 落空隙 → 命中但无段决策（回退 binding）
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 70.0)
        ), 0.0, List.of(new ScoreBand(0, 60, "REJECT", null)));
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.finalDecision()).isNull();
        assertThat(result.hitDecisions()).isEmpty();
    }

    @Test
    void noBands_legacyThresholdBehaviorUnchanged() {
        // bands 空 → 老逻辑：score>=threshold 命中，无 decision
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 70.0)
        ), 60.0, java.util.List.of());
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.score()).isEqualTo(70.0);
        assertThat(result.finalDecision()).isNull();
        assertThat(result.hitDecisions()).isEmpty();
    }

    @Test
    void category_and_decision_areNull_forScorecard() {
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 50.0)
        ), 50.0, java.util.List.of());
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.category()).isNull();
        assertThat(result.decision()).isNull();
    }
}
