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
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 DecisionTreeExecutor 构建全保真 NodeTrace（增量2），并遵循 TraceScope.COLLECT 零分配契约。 */
class DecisionTreeTraceTest {

    /** GTE 叶子算子：metric 值 >= threshold 时命中。 */
    private final ConditionEvaluator gte = (n, c) -> {
        long actual = ((Number) c.getMetric(n.metricCode()).value()).longValue();
        long threshold = ((Number) n.params().get("threshold")).longValue();
        return actual >= threshold;
    };

    private EvalContext ctxWith(Map<String, MetricValue> metrics) {
        RuleEvent event = new RuleEvent("1", "PAY", "transfer", "u1", "e1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
        return new EvalContext("1", event, new Subject("u1", SubjectType.USER, Map.of()),
                metrics, Instant.now());
    }

    private RuleVersionSnapshot treeSnap(AstNode root) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(7L).tenantId("1").sceneCode("PAY").conditionAst(root)
                .addDecisionBinding("APPROVE", 10)
                .addDecisionBinding("REJECT", 5)
                .build();
    }

    @Test
    void treeTrace_conditionTrue_takesThenBranch_withConditionAndLeafChildren() {
        ConditionNode cond = new ConditionNode("GTE", "score", "score>=60",
                Map.of("threshold", 60), null, "LONG");
        DecisionLeafNode thenLeaf = new DecisionLeafNode("APPROVE", "APPROVE");
        DecisionLeafNode elseLeaf = new DecisionLeafNode("REJECT", "REJECT");
        IfNode root = new IfNode(cond, thenLeaf, elseLeaf);
        DecisionTreeExecutor exec = new DecisionTreeExecutor(Map.of("GTE", gte));
        EvalContext ctx = ctxWith(Map.of("score", new MetricValue(100L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(treeSnap(root), ctx);

        // 命中行为不变
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("APPROVE");

        // trace 根为 IfNode，result=condSatisfied
        NodeTrace ifTrace = r.nodeTrace().getFirst();
        assertThat(ifTrace.nodeType()).isEqualTo("IfNode");
        assertThat(ifTrace.result()).isTrue();
        assertThat(ifTrace.ruleVersionId()).isEqualTo(7L);
        assertThat(ifTrace.children()).hasSize(2);

        // children[0] = 条件子树（ConditionNode，带实际值）
        NodeTrace condTrace = ifTrace.children().get(0);
        assertThat(condTrace.nodeType()).isEqualTo("ConditionNode");
        assertThat(condTrace.conditionType()).isEqualTo("GTE");
        assertThat(condTrace.metricCode()).isEqualTo("score");
        assertThat(condTrace.result()).isTrue();
        assertThat(condTrace.actualValue()).isEqualTo(100L);
        assertThat(condTrace.valueSource()).isEqualTo("PROVIDED");

        // children[1] = 命中分支叶子
        NodeTrace leafTrace = ifTrace.children().get(1);
        assertThat(leafTrace.nodeType()).isEqualTo("DecisionLeafNode");
        assertThat(leafTrace.result()).isTrue();
        assertThat(leafTrace.ruleVersionId()).isEqualTo(7L);
    }

    @Test
    void treeTrace_xorCondition_unsupported_errorsNoEvaluator() {
        // 决策树条件不支持 XOR：显式置 NO_EVALUATOR ERROR（设计决策），整规则 miss、不猜分支
        ConditionNode a = new ConditionNode("GTE", "score", "score>=60",
                Map.of("threshold", 60), null, "LONG");
        XorNode xorCond = new XorNode(List.of(a), null);
        IfNode root = new IfNode(xorCond, new DecisionLeafNode("APPROVE", "APPROVE"),
                new DecisionLeafNode("REJECT", "REJECT"));
        DecisionTreeExecutor exec = new DecisionTreeExecutor(Map.of("GTE", gte));
        EvalContext ctx = ctxWith(Map.of("score", new MetricValue(100L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(treeSnap(root), ctx);

        assertThat(r.ruleHit()).isFalse();
        assertThat(r.errorCode()).isEqualTo("NO_EVALUATOR");
    }

    @Test
    void treeTrace_conditionFalse_takesElseBranch() {
        ConditionNode cond = new ConditionNode("GTE", "score", "score>=60",
                Map.of("threshold", 60), null, "LONG");
        IfNode root = new IfNode(cond, new DecisionLeafNode("APPROVE", "APPROVE"),
                new DecisionLeafNode("REJECT", "REJECT"));
        DecisionTreeExecutor exec = new DecisionTreeExecutor(Map.of("GTE", gte));
        EvalContext ctx = ctxWith(Map.of("score", new MetricValue(10L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(treeSnap(root), ctx);

        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("REJECT");
        NodeTrace ifTrace = r.nodeTrace().getFirst();
        assertThat(ifTrace.result()).isFalse();
        assertThat(ifTrace.children()).hasSize(2);
        assertThat(ifTrace.children().get(1).nodeType()).isEqualTo("DecisionLeafNode");
    }

    @Test
    void treeTrace_complexAndCondition_expandsFully() {
        ConditionNode c1 = new ConditionNode("GTE", "score", "score>=60",
                Map.of("threshold", 60), null, "LONG");
        ConditionNode c2 = new ConditionNode("GTE", "age", "age>=18",
                Map.of("threshold", 18), null, "LONG");
        AndNode and = new AndNode(List.of(c1, c2), "both", null);
        IfNode root = new IfNode(and, new DecisionLeafNode("APPROVE", "APPROVE"), null);
        DecisionTreeExecutor exec = new DecisionTreeExecutor(Map.of("GTE", gte));
        EvalContext ctx = ctxWith(Map.of(
                "score", new MetricValue(100L, "LONG", "PROVIDED"),
                "age", new MetricValue(30L, "LONG", "PROVIDED")));

        EvalResult r = exec.execute(treeSnap(root), ctx);

        assertThat(r.ruleHit()).isTrue();
        NodeTrace ifTrace = r.nodeTrace().getFirst();
        assertThat(ifTrace.result()).isTrue();
        // children[0] = AndNode 容器，含两个 ConditionNode 子 trace
        NodeTrace andTrace = ifTrace.children().get(0);
        assertThat(andTrace.nodeType()).isEqualTo("AndNode");
        assertThat(andTrace.result()).isTrue();
        assertThat(andTrace.children()).hasSize(2);
        assertThat(andTrace.children().get(0).metricCode()).isEqualTo("score");
        assertThat(andTrace.children().get(1).metricCode()).isEqualTo("age");
        // children[1] = 命中叶子
        assertThat(ifTrace.children().get(1).nodeType()).isEqualTo("DecisionLeafNode");
    }

    @Test
    void treeTrace_collectDisabled_isEmpty_butDecisionUnchanged() throws Exception {
        ConditionNode cond = new ConditionNode("GTE", "score", "score>=60",
                Map.of("threshold", 60), null, "LONG");
        IfNode root = new IfNode(cond, new DecisionLeafNode("APPROVE", "APPROVE"),
                new DecisionLeafNode("REJECT", "REJECT"));
        DecisionTreeExecutor exec = new DecisionTreeExecutor(Map.of("GTE", gte));
        RuleVersionSnapshot snap = treeSnap(root);
        EvalContext ctx = ctxWith(Map.of("score", new MetricValue(100L, "LONG", "PROVIDED")));

        EvalResult off = ScopedValue.where(TraceScope.COLLECT, false).call(() -> exec.execute(snap, ctx));
        EvalResult on = exec.execute(snap, ctx);

        assertThat(off.nodeTrace()).isEmpty();
        // 命中/决策与 trace-on 完全一致
        assertThat(off.ruleHit()).isEqualTo(on.ruleHit());
        assertThat(off.finalDecision().code()).isEqualTo(on.finalDecision().code());
    }
}
