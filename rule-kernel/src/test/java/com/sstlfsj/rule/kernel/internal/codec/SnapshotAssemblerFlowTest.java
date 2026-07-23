package com.sstlfsj.rule.kernel.internal.codec;

import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowEdge;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DECISION_FLOW 行的 body(FlowBody) 列经 SnapshotAssembler 往返装配验证。 */
class SnapshotAssemblerFlowTest {

    private final ObjectMapper om = JsonMapper.builder().build();
    private final SnapshotAssembler assembler = new SnapshotAssembler();

    private RuleVersionRow flowRow(String bodyJson) {
        return new RuleVersionRow(1L, "scene", 100L, bodyJson, "[]", "[]", "[]",
                RuleKind.DECISION_FLOW.tag(), "HIGHEST_PRIORITY", "[]", "[]", "R_FLOW", 1L, null);
    }

    /** FlowBody 的 flowGraph 经 body 列反序列化后保真：节点/边/入口。 */
    @Test
    void assemblesFlowRowIntoFlowBody() {
        FlowGraph graph = new FlowGraph(
                List.of(new RuleRefNode("n1", "ref.rule"), new OutputNode("out", "PASS")),
                List.of(new FlowEdge("n1", "out", null)),
                "n1");
        String bodyJson = om.writeValueAsString(new FlowBody(graph, Map.of()));

        RuleVersionSnapshot snap = assembler.assemble(flowRow(bodyJson));

        assertThat(snap.body()).isInstanceOf(FlowBody.class);
        FlowBody fb = (FlowBody) snap.body();
        assertThat(fb.flowGraph().inputNodeId()).isEqualTo("n1");
        assertThat(fb.flowGraph().nodes()).hasSize(2);
        assertThat(fb.flowGraph().edges()).containsExactly(new FlowEdge("n1", "out", null));
        assertThat(fb.referencedSnapshots()).isEmpty();
        assertThat(snap.kind()).isEqualTo(RuleKind.DECISION_FLOW.tag());
    }

    /** FlowBody 的 referencedSnapshots 经 body 列反序列化后保真，且嵌套多态 AstNode 还原。 */
    @Test
    void assemblesReferencedSnapshotsWithPolymorphicAst() {
        RuleVersionSnapshot referenced = RuleVersionSnapshot.builder()
                .ruleVersionId(9L)
                .sceneCode("scene")
                .tenantId("100")
                .code("ref.rule")
                .version(2L)
                .kind(RuleKind.AST_BOOLEAN.tag())
                .conditionAst(new ConditionNode("GT", "amount", "LONG", Map.of("threshold", 1000), 0.0))
                .build();
        FlowGraph graph = new FlowGraph(List.of(new OutputNode("out", "PASS")), List.of(), "out");
        String bodyJson = om.writeValueAsString(new FlowBody(graph, Map.of("ref.rule", referenced)));

        RuleVersionSnapshot snap = assembler.assemble(flowRow(bodyJson));

        assertThat(snap.body()).isInstanceOf(FlowBody.class);
        FlowBody fb = (FlowBody) snap.body();
        assertThat(fb.referencedSnapshots()).containsKey("ref.rule");
        RuleVersionSnapshot back = fb.referencedSnapshots().get("ref.rule");
        assertThat(back.code()).isEqualTo("ref.rule");
        assertThat(back.version()).isEqualTo(2L);
        // 嵌套的多态 body/AstNode 经 "type" 判别字段正确还原
        assertThat(back.body()).isInstanceOf(AstBody.class);
        assertThat(((AstBody) back.body()).conditionAst()).isInstanceOf(ConditionNode.class);
        assertThat(((ConditionNode) ((AstBody) back.body()).conditionAst()).metricCode()).isEqualTo("amount");
    }
}
