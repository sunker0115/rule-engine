package com.sstlfsj.rule.kernel.internal.codec;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
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
                  {"type":"ConditionNode","conditionType":"EQ","metricCode":"a","params":{}}
                ]}
                """;
        AstNode node = codec.deserializeAst(json);
        assertInstanceOf(AndNode.class, node);
    }

    @Test
    void deserializePreGates_emptyList() throws Exception {
        List<RuleVersionSnapshot.PreGateConfig> gates = codec.deserializePreGates("[]");
        assertTrue(gates.isEmpty());
    }

    @Test
    void deserializePreGates_rollout() throws Exception {
        String json = "[{\"gateType\":\"ROLLOUT\",\"params\":{\"percentage\":10}}]";
        List<RuleVersionSnapshot.PreGateConfig> gates = codec.deserializePreGates(json);
        assertEquals(1, gates.size());
        assertEquals("ROLLOUT", gates.get(0).gateType());
        assertEquals(10, ((Number) gates.get(0).params().get("percentage")).intValue());
    }

    @Test
    void deserializeDecisionBindings() throws Exception {
        String json = "[{\"decisionCode\":\"BLOCK\",\"priority\":10}]";
        List<RuleVersionSnapshot.DecisionBinding> bindings = codec.deserializeDecisionBindings(json);
        assertEquals(1, bindings.size());
        assertEquals("BLOCK", bindings.get(0).decisionCode());
        assertEquals(10, bindings.get(0).priority());
    }

    @Test
    void deserializeStringList() throws Exception {
        List<String> list = codec.deserializeStringList("[\"ORDER\",\"PAYMENT\"]");
        assertEquals(List.of("ORDER", "PAYMENT"), list);
    }

    @Test
    void deserializeMetricDependencies_parsesCodeAndVersion() throws Exception {
        String json = "[{\"metricCode\":\"balance\",\"metricVersion\":2},{\"metricCode\":\"score\",\"metricVersion\":1}]";
        List<MetricDependency> deps = codec.deserializeMetricDependencies(json);
        assertEquals(2, deps.size());
        assertEquals(new MetricDependency("balance", 2), deps.get(0));
        assertEquals(new MetricDependency("score", 1), deps.get(1));
    }

    @Test
    void deserializeMetricDependencies_emptyArray_returnsEmpty() throws Exception {
        List<MetricDependency> deps = codec.deserializeMetricDependencies("[]");
        assertTrue(deps.isEmpty());
    }

    @Test
    void createMapper_returnsCachedInstance() {
        assertSame(codec.createMapper(), codec.createMapper());
    }

    @Test
    void constructorWithMapper_usesThatMapper() throws Exception {
        // 验证带参构造器：传入的 mapper 被实际使用（通过 createMapper() 取回是同一实例）
        ObjectMapper custom = JsonMapper.builder().build();
        AstJsonCodec codec = new AstJsonCodec(custom);
        assertSame(custom, codec.createMapper());
    }

    @Test
    void serializeAst_includesTypeField() throws Exception {
        // 多态配置迁移到 AstNode 接口注解后，codec mapper 序列化也必须带 "type" 字段
        ConditionNode node = new ConditionNode("GT", "score", null, Map.of("threshold", 80), 0.0);
        String json = codec.createMapper().writeValueAsString(node);
        assertTrue(json.contains("\"type\":\"ConditionNode\""), "缺少 type 字段: " + json);
    }

    @Test
    void deserializeIfNode() throws Exception {
        String json = """
                {"type":"IfNode",
                 "condition":{"type":"ConditionNode","conditionType":"GT","metricCode":"amount","params":{}},
                 "thenBranch":{"type":"DecisionLeafNode","decisionCode":"BLOCK","category":"HIGH_RISK"},
                 "elseBranch":{"type":"DecisionLeafNode","decisionCode":"PASS","category":"LOW_RISK"}}
                """;
        AstNode node = codec.deserializeAst(json);
        assertInstanceOf(IfNode.class, node);
        IfNode ifNode = (IfNode) node;
        assertInstanceOf(ConditionNode.class, ifNode.condition());
        assertInstanceOf(DecisionLeafNode.class, ifNode.thenBranch());
        assertInstanceOf(DecisionLeafNode.class, ifNode.elseBranch());
        assertEquals("BLOCK", ((DecisionLeafNode) ifNode.thenBranch()).decisionCode());
    }

    @Test
    void deserializeIfNode_nullElseBranch() throws Exception {
        String json = """
                {"type":"IfNode",
                 "condition":{"type":"ConditionNode","conditionType":"EQ","metricCode":"x","params":{}},
                 "thenBranch":{"type":"DecisionLeafNode","decisionCode":"REVIEW","category":null},
                 "elseBranch":null}
                """;
        AstNode node = codec.deserializeAst(json);
        assertInstanceOf(IfNode.class, node);
        assertNull(((IfNode) node).elseBranch());
    }

    @Test
    void deserializeDecisionLeafNode() throws Exception {
        String json = """
                {"type":"DecisionLeafNode","decisionCode":"REJECT","category":"FRAUD"}
                """;
        AstNode node = codec.deserializeAst(json);
        assertInstanceOf(DecisionLeafNode.class, node);
        DecisionLeafNode leaf = (DecisionLeafNode) node;
        assertEquals("REJECT", leaf.decisionCode());
        assertEquals("FRAUD", leaf.category());
    }

    @Test
    void deserializeDecisionTableNode() throws Exception {
        String json = """
                {"type":"DecisionTableNode",
                 "columns":[{"metricCode":"amount","operator":"GT"}],
                 "rows":[{"conditions":[1000],"decisionCode":"BLOCK"}]}
                """;
        AstNode node = codec.deserializeAst(json);
        assertInstanceOf(DecisionTableNode.class, node);
        DecisionTableNode table = (DecisionTableNode) node;
        assertEquals(1, table.columns().size());
        assertEquals("amount", table.columns().get(0).metricCode());
        assertEquals(1, table.rows().size());
        assertEquals("BLOCK", table.rows().get(0).decisionCode());
    }
}
