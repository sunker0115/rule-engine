package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
// D42: import 与 InterpretedExecutor 对称，覆盖异常路径的测试用例已存在于下方。
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** TracingInterpretedExecutor 的单测：验证 NodeTrace 收集逻辑与短路行为。 */
class TracingInterpretedExecutorTest {

    private static final String ALWAYS_TRUE  = "ALWAYS_TRUE";
    private static final String ALWAYS_FALSE = "ALWAYS_FALSE";

    private final ConditionEvaluator alwaysTrue  = (node, ctx) -> true;
    private final ConditionEvaluator alwaysFalse = (node, ctx) -> false;

    private TracingInterpretedExecutor executorWith(Map<String, ConditionEvaluator> evaluators) {
        return new TracingInterpretedExecutor(evaluators);
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
        return new ConditionNode(ALWAYS_TRUE, "metric1", null, Map.of(), 0.0);
    }

    private ConditionNode falseNode() {
        return new ConditionNode(ALWAYS_FALSE, "metric2", null, Map.of(), 0.0);
    }

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
    void andNode_shortCircuit_onlyEvaluatesNecessaryChildren() {
        // AND(FALSE, TRUE) 短路：只求值第一个子节点
        // 顶层 trace 1 条（AndNode），AndNode.children 包含 1 条（第一个子节点）
        AstNode ast = new AndNode(List.of(falseNode(), trueNode()), null, null);
        EvalResult result = executorWith(Map.of(
                        ALWAYS_TRUE, alwaysTrue,
                        ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isFalse();
        // 顶层 trace 只有 AndNode 自身
        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace andTrace = result.nodeTrace().get(0);
        assertThat(andTrace.nodeType()).isEqualTo("AndNode");
        assertThat(andTrace.result()).isFalse();
        // 短路后只有第一个子节点进入 children
        assertThat(andTrace.children()).hasSize(1);
        assertThat(andTrace.children().get(0).nodeType()).isEqualTo("ConditionNode");
        assertThat(andTrace.children().get(0).result()).isFalse();
    }

    @Test
    void notNode_inverts_result() {
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
    void orNode_shortCircuit_stopsAtFirstTrue() {
        // OR(TRUE, FALSE) 短路：只求值第一个子节点
        // 顶层 trace 1 条（OrNode），OrNode.children 包含 1 条
        AstNode ast = new OrNode(List.of(trueNode(), falseNode()), null, null);
        EvalResult result = executorWith(Map.of(
                        ALWAYS_TRUE, alwaysTrue,
                        ALWAYS_FALSE, alwaysFalse))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.ruleHit()).isTrue();
        // 顶层 trace 只有 OrNode 自身
        assertThat(result.nodeTrace()).hasSize(1);
        NodeTrace orTrace = result.nodeTrace().get(0);
        assertThat(orTrace.nodeType()).isEqualTo("OrNode");
        assertThat(orTrace.result()).isTrue();
        // 短路后只有第一个子节点进入 children
        assertThat(orTrace.children()).hasSize(1);
        assertThat(orTrace.children().get(0).nodeType()).isEqualTo("ConditionNode");
        assertThat(orTrace.children().get(0).result()).isTrue();
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
        // 子节点也必须携带 ruleVersionId
        assertThat(andTrace.children()).allSatisfy(
                child -> assertThat(child.ruleVersionId()).isEqualTo(1L));
    }

    @Test
    void scorecardRootNode_throwsIllegalState() {
        // TracingInterpretedExecutor 只处理 AST_BOOLEAN 规则，遇到 ScorecardRootNode 应抛出异常
        AstNode ast = new ScorecardRootNode(List.of(), 0.6);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executorWith(Map.of()).execute(snapshot(ast), minimalContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScorecardRootNode");
    }

    @Test
    void xorNode_exactlyOneTrue_producesTraceWithAllChildren() {
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
        // 全量遍历，3 个子节点均有 trace
        assertThat(xorTrace.children()).hasSize(3);
    }

    @Test
    void xorNode_twoTrue_producesTrace_miss() {
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
    void execute_scoreIsNull_forBooleanRules() {
        // AST_BOOLEAN executor 不计算 score，EvalResult.score() 必须为 null
        AstNode ast = trueNode();
        EvalResult result = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.score()).isNull();
    }

    @Test
    void execute_category_and_decision_areNull_forBooleanRules() {
        AstNode ast = trueNode();
        EvalResult result = executorWith(Map.of(ALWAYS_TRUE, alwaysTrue))
                .execute(snapshot(ast), minimalContext());

        assertThat(result.category()).isNull();
        assertThat(result.decision()).isNull();
    }

    @Test
    void ifNode_throwsIllegalState() {
        AstNode ast = new IfNode(trueNode(), new DecisionLeafNode("BLOCK", null), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executorWith(Map.of(ALWAYS_TRUE, alwaysTrue)).execute(snapshot(ast), minimalContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IfNode");
    }

    @Test
    void decisionLeafNode_throwsIllegalState() {
        AstNode ast = new DecisionLeafNode("BLOCK", "HIGH_RISK");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executorWith(Map.of()).execute(snapshot(ast), minimalContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DecisionLeafNode");
    }

    @Test
    void decisionTableNode_throwsIllegalState() {
        AstNode ast = new DecisionTableNode(List.of(), List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> executorWith(Map.of()).execute(snapshot(ast), minimalContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DecisionTableNode");
    }
}
