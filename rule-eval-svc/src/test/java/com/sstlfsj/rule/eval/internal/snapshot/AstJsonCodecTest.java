package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AstJsonCodecTest {

    private final AstJsonCodec codec = new AstJsonCodec();

    @Test
    void deserializeConditionNode() throws Exception {
        String json = """
                {"type":"ConditionNode","conditionType":"GT","metricCode":"score",
                 "params":{"threshold":80}}
                """;
        AstNode node = codec.deserializeAst(json);
        assertInstanceOf(ConditionNode.class, node);
        ConditionNode cond = (ConditionNode) node;
        assertEquals("GT", cond.conditionType());
        assertEquals("score", cond.metricCode());
        assertEquals(80, ((Number) cond.params().get("threshold")).intValue());
    }

    @Test
    void deserializeAndNode_withChildren() throws Exception {
        String json = """
                {"type":"AndNode","children":[
                  {"type":"ConditionNode","conditionType":"EQ","metricCode":"age","params":{}},
                  {"type":"ConditionNode","conditionType":"GT","metricCode":"score","params":{}}
                ]}
                """;
        AstNode node = codec.deserializeAst(json);
        assertInstanceOf(AndNode.class, node);
        assertEquals(2, ((AndNode) node).children().size());
    }

    @Test
    void deserializePreGates_emptyList() throws Exception {
        List<RuleVersionSnapshot.PreGateConfig> gates = codec.deserializePreGates("[]");
        assertTrue(gates.isEmpty());
    }

    @Test
    void deserializePreGates_rollout() throws Exception {
        String json = """
                [{"gateType":"ROLLOUT","params":{"percentage":20}}]
                """;
        List<RuleVersionSnapshot.PreGateConfig> gates = codec.deserializePreGates(json);
        assertEquals(1, gates.size());
        assertEquals("ROLLOUT", gates.get(0).gateType());
        assertEquals(20, ((Number) gates.get(0).params().get("percentage")).intValue());
    }

    @Test
    void deserializeDecisionBindings() throws Exception {
        String json = """
                [{"decisionCode":"REJECT","priority":10}]
                """;
        List<RuleVersionSnapshot.DecisionBinding> bindings = codec.deserializeDecisionBindings(json);
        assertEquals(1, bindings.size());
        assertEquals("REJECT", bindings.get(0).decisionCode());
        assertEquals(10, bindings.get(0).priority());
    }

    @Test
    void deserializeStringList() throws Exception {
        List<String> codes = codec.deserializeStringList("[\"EVENT_A\",\"EVENT_B\"]");
        assertEquals(List.of("EVENT_A", "EVENT_B"), codes);
    }
}
