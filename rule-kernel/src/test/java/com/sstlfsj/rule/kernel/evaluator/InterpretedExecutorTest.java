package com.sstlfsj.rule.kernel.evaluator;

// execute() 委托给 EvalResult.hit()/miss() 工厂方法；ruleHit 断言覆盖两条路径。
// D42: IfNode/DecisionLeafNode/DecisionTableNode 抛 IllegalState，交由专属 Executor 处理。

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
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
                "evt-1", Instant.now(), Map.of(), null, com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
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
    void xorNode_exactlyOneTrue_returns_ruleHit() {
        // XOR(true, false, false) = true
        AstNode ast = new XorNode(List.of(trueNode(), falseNode(), falseNode()), null);
        InterpretedExecutor executor = executorWith(Map.of(
                ALWAYS_TRUE, alwaysTrue,
                ALWAYS_FALSE, alwaysFalse));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void xorNode_allTrue_returns_miss() {
        // XOR(true, true) = false
        AstNode ast = new XorNode(List.of(trueNode(), trueNode()), null);
        InterpretedExecutor executor = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void xorNode_allFalse_returns_miss() {
        // XOR(false, false) = false
        AstNode ast = new XorNode(List.of(falseNode(), falseNode()), null);
        InterpretedExecutor executor = executorWith(Map.of(ALWAYS_FALSE, alwaysFalse));

        EvalResult result = executor.execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void scorecardRootNode_throwsIllegalState() {
        AstNode ast = new ScorecardRootNode(List.of(), 0.6, java.util.List.of());
        InterpretedExecutor executor = executorWith(Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(snapshot(ast), minimalContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScorecardRootNode");
    }

    @Test
    void ifNode_throwsIllegalState() {
        AstNode ast = new IfNode(trueNode(), new DecisionLeafNode("BLOCK", null), null);
        InterpretedExecutor executor = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(snapshot(ast), minimalContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IfNode");
    }

    @Test
    void decisionLeafNode_throwsIllegalState() {
        AstNode ast = new DecisionLeafNode("BLOCK", "HIGH_RISK");
        InterpretedExecutor executor = executorWith(Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(snapshot(ast), minimalContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DecisionLeafNode");
    }

    @Test
    void decisionTableNode_throwsIllegalState() {
        AstNode ast = new DecisionTableNode(List.of(), List.of());
        InterpretedExecutor executor = executorWith(Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executor.execute(snapshot(ast), minimalContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DecisionTableNode");
    }

    // ---- NodeTrace 收集（COLLECT 未绑定默认 true）----

    @Test
    void singleConditionNode_hit_producesTrace() {
        // 单个 ConditionNode 命中，trace 应有 1 条记录，nodeType=ConditionNode，result=true
        AstNode ast = trueNode();
        EvalResult result = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace trace = result.nodeTrace().get(0);
        assertThat(trace.nodeType()).isEqualTo("ConditionNode");
        assertThat(trace.result()).isTrue();
    }

    @Test
    void singleConditionNode_miss_producesTrace() {
        // 单个 ConditionNode 未命中，trace result=false
        AstNode ast = falseNode();
        EvalResult result = executorWith(Map.of(ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace trace = result.nodeTrace().get(0);
        assertThat(trace.nodeType()).isEqualTo("ConditionNode");
        assertThat(trace.result()).isFalse();
    }

    @Test
    void andNode_shortCircuit_traceOnlyHasEvaluatedChildren() {
        // AND(FALSE, TRUE) 短路：只求值第一个子节点
        // 顶层 trace 1 条（AndNode），AndNode.children 包含 1 条（第一个子节点）
        AstNode ast = new AndNode(List.of(falseNode(), trueNode()), null, null);
        EvalResult result = executorWith(Map.of(
                        ALWAYS_TRUE, alwaysTrue,
                        ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace andTrace = result.nodeTrace().get(0);
        assertThat(andTrace.nodeType()).isEqualTo("AndNode");
        assertThat(andTrace.result()).isFalse();
        assertThat(andTrace.children()).hasSize(1);
        assertThat(andTrace.children().get(0).nodeType()).isEqualTo("ConditionNode");
        assertThat(andTrace.children().get(0).result()).isFalse();
    }

    @Test
    void orNode_shortCircuit_traceStopsAtFirstTrue() {
        // OR(TRUE, FALSE) 短路：只求值第一个子节点
        AstNode ast = new OrNode(List.of(trueNode(), falseNode()), null, null);
        EvalResult result = executorWith(Map.of(
                        ALWAYS_TRUE, alwaysTrue,
                        ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace orTrace = result.nodeTrace().get(0);
        assertThat(orTrace.nodeType()).isEqualTo("OrNode");
        assertThat(orTrace.result()).isTrue();
        assertThat(orTrace.children()).hasSize(1);
        assertThat(orTrace.children().get(0).nodeType()).isEqualTo("ConditionNode");
        assertThat(orTrace.children().get(0).result()).isTrue();
    }

    @Test
    void notNode_inverts_result_inTrace() {
        // NOT(TRUE) 结果=false，trace 1 条，result=false
        AstNode ast = new NotNode(trueNode());
        EvalResult result = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace trace = result.nodeTrace().get(0);
        assertThat(trace.nodeType()).isEqualTo("NotNode");
        assertThat(trace.result()).isFalse();
    }

    @Test
    void conditionNode_noEvaluator_traceHasErrorCode() {
        // 无对应 evaluator 时 result=false，errorCode=NO_EVALUATOR
        AstNode ast = new ConditionNode("UNKNOWN_TYPE", "metric1", null, Map.of(), 0.0);
        EvalResult result = executorWith(Map.of())
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace trace = result.nodeTrace().get(0);
        assertThat(trace.errorCode()).isEqualTo("NO_EVALUATOR");
        assertThat(trace.result()).isFalse();
    }

    @Test
    void execute_propagatesRuleVersionId_toAllTraces() {
        // ruleVersionId 必须透传到顶层 trace 及所有子 trace
        AstNode ast = new AndNode(List.of(trueNode(), trueNode()), null, null);
        EvalResult result = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace andTrace = result.nodeTrace().get(0);
        assertThat(andTrace.ruleVersionId()).isEqualTo(1L);
        assertThat(andTrace.children()).allSatisfy(
                child -> assertThat(child.ruleVersionId()).isEqualTo(1L));
    }

    @Test
    void xorNode_exactlyOneTrue_traceHasAllChildren() {
        // XOR 不短路，全量遍历；XOR(TRUE, FALSE, FALSE) = true，children 应有 3 条 trace
        AstNode ast = new XorNode(List.of(trueNode(), falseNode(), falseNode()), null);
        EvalResult result = executorWith(Map.of(
                        ALWAYS_TRUE, alwaysTrue,
                        ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isTrue();
        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace xorTrace = result.nodeTrace().get(0);
        assertThat(xorTrace.nodeType()).isEqualTo("XorNode");
        assertThat(xorTrace.result()).isTrue();
        assertThat(xorTrace.children()).hasSize(3);
    }

    @Test
    void xorNode_twoTrue_traceMiss_hasAllChildren() {
        // XOR(TRUE, TRUE) = false，trace result=false，children 有 2 条
        AstNode ast = new XorNode(List.of(trueNode(), trueNode()), null);
        EvalResult result = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
        NodeTrace xorTrace = result.nodeTrace().get(0);
        assertThat(xorTrace.nodeType()).isEqualTo("XorNode");
        assertThat(xorTrace.result()).isFalse();
        assertThat(xorTrace.children()).hasSize(2);
    }

    @Test
    void execute_scoreCategoryDecision_areNull_forBooleanRules() {
        // AST_BOOLEAN executor 不计算 score/category/decision，相关字段必须为 null
        AstNode ast = trueNode();
        EvalResult result = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.score()).isNull();
        assertThat(result.category()).isNull();
        assertThat(result.decision()).isNull();
    }

    // ---- COLLECT=false：跳过 trace 构建，命中布尔不变 ----

    @Test
    void collectFalse_skipsTrace_butKeepsSameRuleHit() throws Exception {
        // AND(TRUE, OR(FALSE, TRUE)) = true，验证 collect=false 与默认 collect=true 命中一致
        AstNode orNode = new OrNode(List.of(falseNode(), trueNode()), null, null);
        AstNode ast = new AndNode(List.of(trueNode(), orNode), null, null);
        InterpretedExecutor executor = executorWith(Map.of(
                ALWAYS_TRUE, alwaysTrue,
                ALWAYS_FALSE, alwaysFalse));

        // COLLECT 未绑定（默认 true）：收集完整 trace
        EvalResult collected = executor.execute(snapshot(ast), minimalContext());
        // COLLECT=false：跳过 trace 构建
        EvalResult skipped = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> executor.execute(snapshot(ast), minimalContext()));

        assertThat(skipped.nodeTrace()).isEmpty();
        assertThat(collected.nodeTrace()).isNotEmpty();
        assertThat(skipped.ruleHit()).isEqualTo(collected.ruleHit());
    }

    @Test
    void collectFalse_noEvaluator_isMiss_sameAsTrace() throws Exception {
        // 非 trace 快路径(satisfiesBoolean)：无算子 → ERROR → 不命中，与 trace 模式 ruleHit 一致
        AstNode ast = new ConditionNode("UNKNOWN_TYPE", "metric1", null, Map.of(), 0.0);
        InterpretedExecutor executor = executorWith(Map.of());

        EvalResult collected = executor.execute(snapshot(ast), minimalContext());
        EvalResult skipped = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> executor.execute(snapshot(ast), minimalContext()));

        assertThat(collected.ruleHit()).isFalse();
        assertThat(skipped.ruleHit()).isFalse();
        assertThat(skipped.nodeTrace()).isEmpty();
    }
}
