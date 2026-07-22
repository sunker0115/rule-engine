package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.FlowCycleFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.flow.FlowEdge;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.FlowNode;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FlowCycleDetector：单个 DECISION_FLOW 决策图内有向环检测的行为测试。 */
class FlowCycleDetectorTest {

    private static RuleRefNode ref(String id) {
        return new RuleRefNode(id, id + "-rule");
    }

    private static OutputNode out(String id) {
        return new OutputNode(id, id + "-decision");
    }

    private static FlowGraph graph(List<FlowNode> nodes, List<FlowEdge> edges, String input) {
        return new FlowGraph(nodes, edges, input);
    }

    private static FlowEdge edge(String from, String to) {
        return new FlowEdge(from, to, null);
    }

    private static AnalyzableRule flowRule(String code, FlowGraph flow) {
        return new AnalyzableRule(code, 1L, null, List.of(), RuleKind.DECISION_FLOW.tag(), flow);
    }

    @Test
    void empty_rules_yield_empty_findings() {
        assertThat(FlowCycleDetector.detect(List.of())).isEmpty();
    }

    @Test
    void acyclic_flow_yields_no_finding() {
        // n1 -> n2 -> out，无环
        FlowGraph flow = graph(
                List.of(ref("n1"), ref("n2"), out("out")),
                List.of(edge("n1", "n2"), edge("n2", "out")),
                "n1");

        assertThat(FlowCycleDetector.detect(List.of(flowRule("F1", flow)))).isEmpty();
        assertThat(FlowCycleDetector.findCycle(flow)).isEmpty();
    }

    @Test
    void two_node_cycle_is_detected_as_error() {
        // n1 -> n2 -> n1 成环
        FlowGraph flow = graph(
                List.of(ref("n1"), ref("n2")),
                List.of(edge("n1", "n2"), edge("n2", "n1")),
                "n1");

        List<FlowCycleFinding> findings = FlowCycleDetector.detect(List.of(flowRule("F1", flow)));

        assertThat(findings).hasSize(1);
        FlowCycleFinding f = findings.getFirst();
        assertThat(f.ruleCode()).isEqualTo("F1");
        assertThat(f.severity()).isEqualTo(Severity.ERROR);
        assertThat(f.cycleNodeIds()).containsExactly("n1", "n2");
        assertThat(f.reason()).contains("n1").contains("n2");
    }

    @Test
    void cycle_excludes_non_cycle_prefix_reachable_before_the_loop() {
        // a -> b -> c -> b：a 是环前缀(不在环内)，DFS 从 a 出发经 b 才成环 → cycle = [b, c]，须裁掉 a
        FlowGraph flow = graph(
                List.of(ref("a"), ref("b"), ref("c")),
                List.of(edge("a", "b"), edge("b", "c"), edge("c", "b")),
                "a");

        assertThat(FlowCycleDetector.findCycle(flow)).containsExactly("b", "c");
    }

    @Test
    void self_loop_is_detected() {
        // n1 -> n1 自环
        FlowGraph flow = graph(
                List.of(ref("n1")),
                List.of(edge("n1", "n1")),
                "n1");

        assertThat(FlowCycleDetector.findCycle(flow)).containsExactly("n1");
    }

    @Test
    void cycle_in_component_unreachable_from_input_is_still_detected() {
        // 入口分量无环(in -> out)，但断开的 c1 <-> c2 分量成环 → 整图分析仍应报环
        FlowGraph flow = graph(
                List.of(ref("in"), out("out"), ref("c1"), ref("c2")),
                List.of(edge("in", "out"), edge("c1", "c2"), edge("c2", "c1")),
                "in");

        assertThat(FlowCycleDetector.findCycle(flow)).containsExactlyInAnyOrder("c1", "c2");
    }

    @Test
    void non_flow_rule_is_skipped() {
        // AST_BOOLEAN 规则(flowGraph=null)不参与环检测
        AnalyzableRule astRule = new AnalyzableRule("R-ast", 1L, null, List.of(),
                RuleKind.AST_BOOLEAN.tag());

        assertThat(FlowCycleDetector.detect(List.of(astRule))).isEmpty();
    }

    @Test
    void findings_sorted_by_rule_code_then_version() {
        FlowGraph cyclic = graph(
                List.of(ref("a"), ref("b")),
                List.of(edge("a", "b"), edge("b", "a")),
                "a");
        AnalyzableRule f2 = new AnalyzableRule("F2", 1L, null, List.of(),
                RuleKind.DECISION_FLOW.tag(), cyclic);
        AnalyzableRule f1 = new AnalyzableRule("F1", 5L, null, List.of(),
                RuleKind.DECISION_FLOW.tag(), cyclic);

        List<FlowCycleFinding> findings = FlowCycleDetector.detect(List.of(f2, f1));

        assertThat(findings).extracting(FlowCycleFinding::ruleCode).containsExactly("F1", "F2");
    }
}
