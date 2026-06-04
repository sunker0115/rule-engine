package com.sstlfsj.rule.kernel.evaluator;

// execute() 委托给 EvalResult.hit()/miss() 工厂方法；ruleHit 断言覆盖两条路径。

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InterpretedExecutorTest {

    private static final String ALWAYS_TRUE = "ALWAYS_TRUE";
    private static final String ALWAYS_FALSE = "ALWAYS_FALSE";

    private final ConditionEvaluator alwaysTrue = (node, ctx) -> true;
    private final ConditionEvaluator alwaysFalse = (node, ctx) -> false;

    private InterpretedExecutor executorWith(Map<String, ConditionEvaluator> evaluators) {
        return new InterpretedExecutor(evaluators);
    }

    private EvalContext minimalContext() {
        RuleEvent event = new RuleEvent("t1", "scene1", "ORDER_PLACED", "u1",
                "evt-1", Instant.now(), Map.of(), null);
        return new EvalContext("t1", event, null, Map.of());
    }

    private RuleVersionSnapshot snapshot(AstNode ast) {
        return new RuleVersionSnapshot(1L, "scene1", "t1", ast, null, null, null, null);
    }

    private ConditionNode trueNode() {
        return new ConditionNode(ALWAYS_TRUE, null, null, Map.of(), 0.0);
    }

    private ConditionNode falseNode() {
        return new ConditionNode(ALWAYS_FALSE, null, null, Map.of(), 0.0);
    }

    @Test
    void andNode_allChildren_true_returns_ruleHit() {
        AstNode ast = new AndNode(List.of(trueNode(), trueNode(), trueNode()), null, null);
        InterpretedExecutor executor = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void andNode_oneChild_false_returns_miss() {
        AstNode ast = new AndNode(List.of(trueNode(), falseNode(), trueNode()), null, null);
        InterpretedExecutor executor = executorWith(Map.of(
                ALWAYS_TRUE, alwaysTrue,
                ALWAYS_FALSE, alwaysFalse));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void andNode_shortCircuits_after_first_false() {
        AtomicInteger callCount = new AtomicInteger(0);
        ConditionEvaluator counting = (node, ctx) -> {
            callCount.incrementAndGet();
            return false;
        };
        // AND(counting_false, counting_false, counting_false) — 第一个 false 后必须短路停止
        ConditionNode countingNode = new ConditionNode("COUNTING", null, null, Map.of(), 0.0);
        AstNode ast = new AndNode(List.of(countingNode, countingNode, countingNode), null, null);
        InterpretedExecutor executor = executorWith(Map.of("COUNTING", counting));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void orNode_oneChild_true_returns_ruleHit() {
        AstNode ast = new OrNode(List.of(falseNode(), trueNode(), falseNode()), null, null);
        InterpretedExecutor executor = executorWith(Map.of(
                ALWAYS_TRUE, alwaysTrue,
                ALWAYS_FALSE, alwaysFalse));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void orNode_allFalse_returns_miss() {
        AstNode ast = new OrNode(List.of(falseNode(), falseNode()), null, null);
        InterpretedExecutor executor = executorWith(Map.of(ALWAYS_FALSE, alwaysFalse));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void notNode_inverts_true_to_false() {
        AstNode ast = new NotNode(trueNode());
        InterpretedExecutor executor = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void notNode_inverts_false_to_true() {
        AstNode ast = new NotNode(falseNode());
        InterpretedExecutor executor = executorWith(Map.of(ALWAYS_FALSE, alwaysFalse));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void nested_and_or_evaluated_correctly() {
        // AND(true, OR(false, true)) = true，验证嵌套求值正确性
        AstNode orNode = new OrNode(List.of(falseNode(), trueNode()), null, null);
        AstNode ast = new AndNode(List.of(trueNode(), orNode), null, null);
        InterpretedExecutor executor = executorWith(Map.of(
                ALWAYS_TRUE, alwaysTrue,
                ALWAYS_FALSE, alwaysFalse));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void scorecardRootNode_throwsIllegalState() {
        // InterpretedExecutor 只处理 AST_BOOLEAN 规则，遇到 ScorecardRootNode 应抛出异常
        AstNode ast = new ScorecardRootNode(List.of(), 0.6);
        InterpretedExecutor executor = executorWith(Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(snapshot(ast), minimalContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScorecardRootNode");
    }
}
