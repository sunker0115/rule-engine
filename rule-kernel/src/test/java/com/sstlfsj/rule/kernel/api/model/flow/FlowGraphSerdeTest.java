package com.sstlfsj.rule.kernel.api.model.flow;

import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FlowGraph Jackson 多态往返：4 种节点 + 分支边判别正确、可选键缺失不报错。 */
class FlowGraphSerdeTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void roundTripsAllNodeTypesAndBranchEdges() {
        FlowGraph graph = new FlowGraph(
                List.of(
                        new RuleRefNode("n1", "blacklist_check"),
                        new SwitchNode("n2", ExpressionLang.CEL, "hitDecisions[0].code", List.of("PASS", "REJECT")),
                        new TransformNode("n3", ExpressionLang.CEL, "metrics.score * 1.5", "riskScore"),
                        new OutputNode("n4", "REVIEW")),
                List.of(
                        new FlowEdge("n1", "n2", null),
                        new FlowEdge("n2", "n3", "PASS"),
                        new FlowEdge("n3", "n4", null)),
                "n1");

        String json = mapper.writeValueAsString(graph);
        FlowGraph back = mapper.readValue(json, FlowGraph.class);

        assertThat(back.inputNodeId()).isEqualTo("n1");
        assertThat(back.nodes()).hasSize(4);
        assertThat(back.nodes().get(0)).isInstanceOf(RuleRefNode.class);
        assertThat(back.nodes().get(1)).isInstanceOf(SwitchNode.class);
        assertThat(back.nodes().get(2)).isInstanceOf(TransformNode.class);
        assertThat(back.nodes().get(3)).isInstanceOf(OutputNode.class);
        assertThat(((SwitchNode) back.nodes().get(1)).caseKeys()).containsExactly("PASS", "REJECT");
        assertThat(((SwitchNode) back.nodes().get(1)).lang()).isEqualTo(ExpressionLang.CEL);
        assertThat(((TransformNode) back.nodes().get(2)).outputKey()).isEqualTo("riskScore");
        assertThat(back.edges()).hasSize(3);
        assertThat(back.edges().get(1).caseKey()).isEqualTo("PASS");
        assertThat(back.edges().get(0).caseKey()).isNull();
    }

    @Test
    void typeDiscriminatorSerialized() {
        String json = mapper.writeValueAsString(new OutputNode("o1", "PASS"));
        assertThat(json).contains("\"type\":\"OutputNode\"");
    }

    @Test
    void missingOptionalKeysDoNotThrow() {
        // 缺 edges 键 / Switch 缺 caseKeys：无 primitive 字段，不应报错，list 兜底为空
        FlowGraph g = mapper.readValue(
                "{\"inputNodeId\":\"n1\",\"nodes\":[{\"type\":\"RuleRefNode\",\"id\":\"n1\",\"ruleCode\":\"x\"}]}",
                FlowGraph.class);
        assertThat(g.nodes()).hasSize(1);
        assertThat(g.edges()).isEmpty();
    }
}
