package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.FlowDeadNodeFinding;
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

/** FlowReachabilityDetector：单个 DECISION_FLOW 决策图内从入口不可达死节点检测的行为测试。 */
class FlowReachabilityDetectorTest {

    private static RuleRefNode ref(String id) {
        return new RuleRefNode(id, id + "-rule");
    }

    private static OutputNode out(String id) {
        return new OutputNode(id, id + "-decision");
    }

    private static FlowEdge edge(String from, String to) {
        return new FlowEdge(from, to, null);
    }

    private static FlowGraph graph(List<FlowNode> nodes, List<FlowEdge> edges, String input) {
        return new FlowGraph(nodes, edges, input);
    }

    private static AnalyzableRule flowRule(String code, FlowGraph flow) {
        return new AnalyzableRule(code, 1L, null, List.of(), RuleKind.DECISION_FLOW.tag(), flow);
    }

    @Test
    void empty_rules_yield_empty_findings() {
        assertThat(FlowReachabilityDetector.detect(List.of())).isEmpty();
    }

    @Test
    void all_nodes_reachable_yields_no_finding() {
        // in -> mid -> out，全部从入口可达
        FlowGraph flow = graph(
                List.of(ref("in"), ref("mid"), out("out")),
                List.of(edge("in", "mid"), edge("mid", "out")),
                "in");

        assertThat(FlowReachabilityDetector.detect(List.of(flowRule("F1", flow)))).isEmpty();
        assertThat(FlowReachabilityDetector.deadNodes(flow)).isEmpty();
    }

    @Test
    void node_unreachable_from_input_is_dead() {
        // dead 有出边(非孤儿)但从 in 走不到 → 死节点
        FlowGraph flow = graph(
                List.of(ref("in"), out("out"), ref("dead")),
                List.of(edge("in", "out"), edge("dead", "out")),
                "in");

        List<FlowDeadNodeFinding> findings = FlowReachabilityDetector.detect(List.of(flowRule("F1", flow)));

        assertThat(findings).hasSize(1);
        FlowDeadNodeFinding f = findings.getFirst();
        assertThat(f.ruleCode()).isEqualTo("F1");
        assertThat(f.deadNodeId()).isEqualTo("dead");
        assertThat(f.severity()).isEqualTo(Severity.WARN);
        assertThat(f.reason()).contains("dead").contains("不可达");
    }

    @Test
    void multiple_dead_nodes_sorted_by_id() {
        // zulu / alpha 均从 in 不可达 → 按 id 升序
        FlowGraph flow = graph(
                List.of(ref("in"), out("out"), ref("zulu"), ref("alpha")),
                List.of(edge("in", "out"), edge("zulu", "alpha")),
                "in");

        List<String> dead = FlowReachabilityDetector.deadNodes(flow);

        assertThat(dead).containsExactly("alpha", "zulu");
    }

    @Test
    void non_flow_rule_is_skipped() {
        AnalyzableRule astRule = new AnalyzableRule("R-ast", 1L, null, List.of(),
                RuleKind.AST_BOOLEAN.tag());

        assertThat(FlowReachabilityDetector.detect(List.of(astRule))).isEmpty();
    }
}
