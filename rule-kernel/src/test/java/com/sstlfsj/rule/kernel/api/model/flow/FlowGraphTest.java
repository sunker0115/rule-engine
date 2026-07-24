package com.sstlfsj.rule.kernel.api.model.flow;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FlowGraphTest {

    @Test
    void threeArgConstructor_defaultsParamsToEmptyMap() {
        FlowGraph graph = new FlowGraph(
                List.of(new OutputNode("out", "PASS")), List.of(), "out");
        assertThat(graph.params()).isEmpty();
    }

    @Test
    void fourArgConstructor_preservesParams() {
        Map<String, Object> params = Map.of("threshold", 100);
        FlowGraph graph = new FlowGraph(
                List.of(new OutputNode("out", "PASS")), List.of(), "out", params);
        assertThat(graph.params()).containsEntry("threshold", 100);
    }

    @Test
    void paramsAreImmutable() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        FlowGraph graph = new FlowGraph(List.of(), List.of(), "in", mutable);
        mutable.put("extra", "x");
        assertThat(graph.params()).hasSize(1);
    }

    @Test
    void nullParams_defaultsToEmpty() {
        FlowGraph graph = new FlowGraph(List.of(), List.of(), "in", null);
        assertThat(graph.params()).isEmpty();
    }
}
