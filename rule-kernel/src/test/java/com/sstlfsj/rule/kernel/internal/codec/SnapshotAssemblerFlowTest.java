package com.sstlfsj.rule.kernel.internal.codec;

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

/** DECISION_FLOW 行的 flow_graph / referenced_snapshots 列经 SnapshotAssembler 往返装配验证。 */
class SnapshotAssemblerFlowTest {

    private final ObjectMapper om = JsonMapper.builder().build();
    private final SnapshotAssembler assembler = new SnapshotAssembler();

    /** flow_graph JSON 列反序列化后填入 snapshot.flowGraph，节点/边/入口保真。 */
    @Test
    void assemblesFlowRowIntoSnapshotFlowGraph() {
        FlowGraph graph = new FlowGraph(
                List.of(new RuleRefNode("n1", "ref.rule"), new OutputNode("out", "PASS")),
                List.of(new FlowEdge("n1", "out", null)),
                "n1");
        String flowGraphJson = om.writeValueAsString(graph);

        RuleVersionRow row = new RuleVersionRow(
                1L, "scene", 100L,
                null,                       // conditionAstJson：flow 规则无 AST
                "[]", "[]", "[]",
                RuleKind.DECISION_FLOW.tag(), "HIGHEST_PRIORITY",
                "[]", "[]", "R_FLOW", 1L,
                null,                       // scriptSourceJson
                flowGraphJson,
                null,                       // referencedSnapshotsJson
                null);                      // defaultParamsJson

        RuleVersionSnapshot snap = assembler.assemble(row);

        assertThat(snap.conditionAst()).isNull();
        assertThat(snap.script()).isNull();
        assertThat(snap.flowGraph()).isNotNull();
        assertThat(snap.flowGraph().inputNodeId()).isEqualTo("n1");
        assertThat(snap.flowGraph().nodes()).hasSize(2);
        assertThat(snap.flowGraph().edges()).containsExactly(new FlowEdge("n1", "out", null));
        assertThat(snap.referencedSnapshots()).isEmpty();
        assertThat(snap.kind()).isEqualTo(RuleKind.DECISION_FLOW.tag());
    }

    /** referenced_snapshots JSON 列反序列化后填入 snapshot.referencedSnapshots，且嵌套多态 AstNode 保真。 */
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
        String referencedJson = om.writeValueAsString(Map.of("ref.rule", referenced));

        RuleVersionRow row = new RuleVersionRow(
                1L, "scene", 100L,
                null,
                "[]", "[]", "[]",
                RuleKind.DECISION_FLOW.tag(), "HIGHEST_PRIORITY",
                "[]", "[]", "R_FLOW", 1L,
                null, null,
                referencedJson,
                null);

        RuleVersionSnapshot snap = assembler.assemble(row);

        assertThat(snap.referencedSnapshots()).containsKey("ref.rule");
        RuleVersionSnapshot back = snap.referencedSnapshots().get("ref.rule");
        assertThat(back.code()).isEqualTo("ref.rule");
        assertThat(back.version()).isEqualTo(2L);
        // 嵌套的多态 AstNode 经 "type" 判别字段正确还原为 ConditionNode
        assertThat(back.conditionAst()).isInstanceOf(ConditionNode.class);
        assertThat(((ConditionNode) back.conditionAst()).metricCode()).isEqualTo("amount");
    }
}
