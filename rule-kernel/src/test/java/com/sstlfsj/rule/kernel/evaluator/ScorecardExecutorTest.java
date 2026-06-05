package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
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
                "evt-1", Instant.now(), Map.of(), null);
        return new EvalContext("t1", event, null, Map.of());
    }

    private RuleVersionSnapshot snapshot(ScorecardRootNode root) {
        return new RuleVersionSnapshot(1L, "scene1", "t1", root, null, null, null, null);
    }

    @Test
    void allConditionsMet_scoreEqualsSum() {
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 30.0),
                new ConditionNode(ALWAYS_TRUE, "m2", null, Map.of(), 70.0)
        ), 80.0);
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
        ), 80.0);
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
        ), 50.0);
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.score()).isEqualTo(50.0);
    }

    @Test
    void noConditions_scoreZero_miss() {
        ScorecardRootNode root = new ScorecardRootNode(List.of(), 1.0);
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
        ), 100.0);
        EvalResult result = new ScorecardExecutor(
                Map.of(ALWAYS_TRUE, alwaysTrue, ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(root), ctx());
        assertThat(result.nodeTrace()).hasSize(2);
    }

    @Test
    void nullWeight_conditionMet_doesNotAccumulateAndNoNpe() {
        // weight=null 时即使条件命中也不累加分数，且不抛 NPE（AST_BOOLEAN 场景兼容）
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), null)
        ), 0.0);
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void category_and_decision_areNull_forScorecard() {
        ScorecardRootNode root = new ScorecardRootNode(List.of(
                new ConditionNode(ALWAYS_TRUE, "m1", null, Map.of(), 50.0)
        ), 50.0);
        EvalResult result = new ScorecardExecutor(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(root), ctx());
        assertThat(result.category()).isNull();
        assertThat(result.decision()).isNull();
    }
}
