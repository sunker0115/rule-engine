package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleBody 三变体 Jackson 多态往返：判别 type 正确、内层载荷恢复、instanceof 类型对。 */
class RuleBodySerdeTest {

    private final ObjectMapper om = JsonMapper.builder().build();

    @Test
    void astBody_roundTrips() throws Exception {
        RuleBody b = new AstBody(new ConditionNode("GT", "score", null, Map.of("threshold", 80), null));
        String json = om.writeValueAsString(b);
        assertThat(json).contains("\"type\":\"AstBody\"");
        RuleBody back = om.readValue(json, RuleBody.class);
        assertThat(back).isInstanceOf(AstBody.class);
        assertThat(((AstBody) back).conditionAst()).isInstanceOf(ConditionNode.class);
    }

    @Test
    void scriptBody_roundTrips() throws Exception {
        RuleBody b = new ScriptBody(new ScriptSource("score > 80", "CEL"));
        RuleBody back = om.readValue(om.writeValueAsString(b), RuleBody.class);
        assertThat(back).isInstanceOf(ScriptBody.class);
        assertThat(((ScriptBody) back).script().lang()).isEqualTo("CEL");
    }

    @Test
    void flowBody_roundTrips() throws Exception {
        FlowGraph g = new FlowGraph(List.of(), List.of(), "in");
        RuleBody b = new FlowBody(g, Map.of());
        RuleBody back = om.readValue(om.writeValueAsString(b), RuleBody.class);
        assertThat(back).isInstanceOf(FlowBody.class);
        assertThat(((FlowBody) back).flowGraph().inputNodeId()).isEqualTo("in");
    }
}
