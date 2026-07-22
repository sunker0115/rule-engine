package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.model.FlowNodeType;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.NodeType;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.flow.FlowEdge;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.FlowNode;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode;
import com.sstlfsj.rule.kernel.api.model.flow.SwitchNode;
import com.sstlfsj.rule.kernel.api.model.flow.TransformNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.FlowExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** FlowExecutor 图遍历：RuleRef/Switch/Transform/Output、flow 命名空间、RuleRef 隔离、trace、错误。 */
class FlowExecutorTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    // ---- stubs ----

    private record StubCompiled(String source) implements CompiledExpression {
        @Override
        public Set<String> referencedVariables() {
            return Set.of();
        }
    }

    private static final class TestEngine implements ExpressionEngine {
        private final BiFunction<String, Map<String, Object>, Object> fn;

        TestEngine(BiFunction<String, Map<String, Object>, Object> fn) {
            this.fn = fn;
        }

        @Override
        public String lang() {
            return "CEL";
        }

        @Override
        public CompiledExpression compile(String source) {
            return new StubCompiled(source);
        }

        @Override
        public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
            return fn.apply(((StubCompiled) compiled).source(), bindings);
        }
    }

    private static final class TestLeaf implements RuleVersionExecutor {
        private final Map<String, EvalResult> byCode;
        private EvalContext lastCtx;

        TestLeaf(Map<String, EvalResult> byCode) {
            this.byCode = byCode;
        }

        @Override
        public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
            this.lastCtx = ctx;
            return byCode.getOrDefault(snapshot.code(), EvalResult.miss());
        }
    }

    // ---- helpers ----

    private static EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene", "TXN", "u1", "e1", NOW,
                Map.of(), Map.of(), EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), NOW);
    }

    private static RuleVersionSnapshot leafSnap(long id, String code) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(id).code(code).kind(RuleKind.AST_BOOLEAN.tag()).build();
    }

    private static FlowExecutor executor(BiFunction<String, Map<String, Object>, Object> fn,
                                         Map<String, EvalResult> leafResults) {
        return new FlowExecutor(
                Map.of(RuleKind.AST_BOOLEAN.tag(), new TestLeaf(leafResults)),
                Map.of("CEL", new TestEngine(fn)));
    }

    // ---- tests ----

    @Test
    void singleRuleRefThenOutput() {
        FlowGraph g = new FlowGraph(
                List.of(new RuleRefNode("n1", "bl"), new OutputNode("n2", "REVIEW")),
                List.of(new FlowEdge("n1", "n2", null)), "n1");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).code("flow1").kind(RuleKind.DECISION_FLOW.tag())
                .flowGraph(g)
                .addReferencedSnapshot("bl", leafSnap(1L, "bl"))
                .addDecisionBinding("REVIEW", "复核", 50)
                .build();

        EvalResult r = executor((e, b) -> null, Map.of("bl", EvalResult.hit())).execute(snap, ctx());

        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("REVIEW");
    }

    @Test
    void switchRoutesToMatchingBranch() {
        FlowGraph g = new FlowGraph(
                List.of(new SwitchNode("n1", ExpressionLang.CEL, "route", List.of("A", "B")),
                        new OutputNode("nA", "PASS"), new OutputNode("nB", "REJECT")),
                List.of(new FlowEdge("n1", "nA", "A"), new FlowEdge("n1", "nB", "B")), "n1");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).code("flow1").kind(RuleKind.DECISION_FLOW.tag())
                .flowGraph(g)
                .addDecisionBinding("PASS", "放行", 10)
                .addDecisionBinding("REJECT", "拒绝", 90)
                .build();

        EvalResult toA = executor((e, b) -> "A", Map.of()).execute(snap, ctx());
        assertThat(toA.finalDecision().code()).isEqualTo("PASS");

        EvalResult toB = executor((e, b) -> "B", Map.of()).execute(snap, ctx());
        assertThat(toB.finalDecision().code()).isEqualTo("REJECT");
    }

    @Test
    void transformWritesFlowVarThenSwitchReadsIt() {
        FlowGraph g = new FlowGraph(
                List.of(new TransformNode("n1", ExpressionLang.CEL, "calc", "score"),
                        new SwitchNode("n2", ExpressionLang.CEL, "gate", List.of("hi", "lo")),
                        new OutputNode("nh", "REVIEW"), new OutputNode("nl", "PASS")),
                List.of(new FlowEdge("n1", "n2", null),
                        new FlowEdge("n2", "nh", "hi"), new FlowEdge("n2", "nl", "lo")), "n1");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).code("flow1").kind(RuleKind.DECISION_FLOW.tag())
                .flowGraph(g)
                .addDecisionBinding("REVIEW", "复核", 50)
                .addDecisionBinding("PASS", "放行", 10)
                .build();

        BiFunction<String, Map<String, Object>, Object> fn = (expr, b) -> {
            if (expr.equals("calc")) return 90;
            // gate 读上游 Transform 写入的 flow.score，验证 flow 命名空间可见
            @SuppressWarnings("unchecked")
            Map<String, Object> flow = (Map<String, Object>) b.get("flow");
            double score = ((Number) flow.get("score")).doubleValue();
            return score > 80 ? "hi" : "lo";
        };

        EvalResult r = executor(fn, Map.of()).execute(snap, ctx());
        assertThat(r.finalDecision().code()).isEqualTo("REVIEW");
    }

    @Test
    void ruleRefReceivesOriginalCtxWithoutFlowVars() {
        FlowGraph g = new FlowGraph(
                List.of(new TransformNode("n1", ExpressionLang.CEL, "calc", "x"),
                        new RuleRefNode("n2", "bl"), new OutputNode("n3", "PASS")),
                List.of(new FlowEdge("n1", "n2", null), new FlowEdge("n2", "n3", null)), "n1");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).code("flow1").kind(RuleKind.DECISION_FLOW.tag())
                .flowGraph(g)
                .addReferencedSnapshot("bl", leafSnap(1L, "bl"))
                .addDecisionBinding("PASS", "放行", 10)
                .build();

        TestLeaf leaf = new TestLeaf(Map.of("bl", EvalResult.hit()));
        FlowExecutor exec = new FlowExecutor(
                Map.of(RuleKind.AST_BOOLEAN.tag(), leaf),
                Map.of("CEL", new TestEngine((e, b) -> 1)));
        EvalContext original = ctx();

        exec.execute(snap, original);

        // 被引规则拿到的必须是原始 ctx（EvalContext 无 flow 字段，隔离是结构性的）
        assertThat(leaf.lastCtx).isSameAs(original);
    }

    @Test
    void noOutputReachedYieldsMiss() {
        FlowGraph g = new FlowGraph(
                List.of(new SwitchNode("n1", ExpressionLang.CEL, "route", List.of("A")),
                        new OutputNode("nA", "PASS")),
                List.of(new FlowEdge("n1", "nA", "A")), "n1");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).code("flow1").kind(RuleKind.DECISION_FLOW.tag())
                .flowGraph(g).addDecisionBinding("PASS", "放行", 10).build();

        // route 返回无匹配 case 且无 default 出边 → 终止，无 Output → miss
        EvalResult r = executor((e, b) -> "Z", Map.of()).execute(snap, ctx());
        assertThat(r.ruleHit()).isFalse();
        assertThat(r.finalDecision()).isNull();
    }

    @Test
    void missingFlowGraphYieldsError() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).code("flow1").kind(RuleKind.DECISION_FLOW.tag()).build();

        EvalResult r = executor((e, b) -> null, Map.of()).execute(snap, ctx());
        assertThat(r.ruleHit()).isFalse();
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.FLOW_GRAPH_MISSING.name());
    }

    @Test
    void outputWithUnboundDecisionCodeYieldsError() {
        FlowGraph g = new FlowGraph(
                List.of(new OutputNode("n1", "UNKNOWN")), List.of(), "n1");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).code("flow1").kind(RuleKind.DECISION_FLOW.tag())
                .flowGraph(g).addDecisionBinding("REVIEW", "复核", 50).build();

        EvalResult r = executor((e, b) -> null, Map.of()).execute(snap, ctx());
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.INVALID_DECISION_CODE.name());
    }

    @Test
    void traceCollectedWithFlowTagsAndLeafSubtree() {
        NodeTrace leafChild = NodeTrace.container(NodeType.AND, true, List.of(), 1L);
        EvalResult leafHit = new EvalResult(true, null, List.of(), List.of(leafChild),
                null, null, null, null);
        FlowGraph g = new FlowGraph(
                List.of(new RuleRefNode("n1", "bl"), new OutputNode("n2", "REVIEW")),
                List.of(new FlowEdge("n1", "n2", null)), "n1");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).code("flow1").kind(RuleKind.DECISION_FLOW.tag())
                .flowGraph(g)
                .addReferencedSnapshot("bl", leafSnap(1L, "bl"))
                .addDecisionBinding("REVIEW", "复核", 50)
                .build();

        EvalResult r = executor((e, b) -> null, Map.of("bl", leafHit)).execute(snap, ctx());

        assertThat(r.nodeTrace()).hasSize(2);
        NodeTrace refTrace = r.nodeTrace().get(0);
        assertThat(refTrace.nodeType()).isEqualTo(FlowNodeType.RULEREF.tag());
        // RuleRef 节点 children 挂被引规则的 trace 子树
        assertThat(refTrace.children()).hasSize(1);
        assertThat(r.nodeTrace().get(1).nodeType()).isEqualTo(FlowNodeType.OUTPUT.tag());
    }

    @Test
    void traceSkippedWhenCollectDisabled() throws Exception {
        FlowGraph g = new FlowGraph(
                List.of(new RuleRefNode("n1", "bl"), new OutputNode("n2", "REVIEW")),
                List.of(new FlowEdge("n1", "n2", null)), "n1");
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).code("flow1").kind(RuleKind.DECISION_FLOW.tag())
                .flowGraph(g)
                .addReferencedSnapshot("bl", leafSnap(1L, "bl"))
                .addDecisionBinding("REVIEW", "复核", 50)
                .build();
        FlowExecutor exec = executor((e, b) -> null, Map.of("bl", EvalResult.hit()));

        EvalResult r = ScopedValue.where(TraceScope.COLLECT, false).call(() -> exec.execute(snap, ctx()));

        assertThat(r.ruleHit()).isTrue();
        assertThat(r.nodeTrace()).isEmpty();
    }
}
