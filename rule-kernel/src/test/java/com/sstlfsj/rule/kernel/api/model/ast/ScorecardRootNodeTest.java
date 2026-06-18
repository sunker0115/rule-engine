package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScorecardRootNodeTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void nullConditions_treatedAsEmptyList() {
        ScorecardRootNode node = new ScorecardRootNode(null, 0.6, java.util.List.of());
        assertNotNull(node.conditions());
        assertTrue(node.conditions().isEmpty());
    }

    @Test
    void conditions_areImmutable() {
        List<ConditionNode> mutable = new ArrayList<>();
        mutable.add(new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4));
        ScorecardRootNode node = new ScorecardRootNode(mutable, 0.6, java.util.List.of());
        mutable.add(new ConditionNode("LT", "score", null, Map.of("threshold", 100), 0.6));
        assertEquals(1, node.conditions().size(), "构造后修改原始列表不应影响 ScorecardRootNode");
    }

    @Test
    void conditions_listIsUnmodifiable() {
        ScorecardRootNode node = new ScorecardRootNode(
                List.of(new ConditionNode("GT", "score", null, Map.of(), 0.5)), 0.6, java.util.List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> node.conditions().add(new ConditionNode("EQ", "x", null, Map.of(), 0.0)));
    }

    @Test
    void threshold_retainsSpecifiedValue() {
        ScorecardRootNode node = new ScorecardRootNode(List.of(), 0.75, java.util.List.of());
        assertEquals(0.75, node.threshold(), 1e-9);
    }

    @Test
    void recordEquality_byValue() {
        ConditionNode cond = new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4);
        ScorecardRootNode a = new ScorecardRootNode(List.of(cond), 0.6, java.util.List.of());
        ScorecardRootNode b = new ScorecardRootNode(List.of(cond), 0.6, java.util.List.of());
        assertEquals(a, b);
    }

    @Test
    void implementsAstNode() {
        ScorecardRootNode node = new ScorecardRootNode(List.of(), 0.5, java.util.List.of());
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void compatConstructor_defaultsBandsEmpty() {
        ScorecardRootNode node = new ScorecardRootNode(List.of(), 60.0, java.util.List.of());
        assertTrue(node.bands().isEmpty());
        assertEquals(60.0, node.threshold(), 1e-9);
    }

    @Test
    void bands_nullNormalizedToEmpty() {
        ScorecardRootNode node = new ScorecardRootNode(List.of(), 60.0, null);
        assertTrue(node.bands().isEmpty());
    }

    @Test
    void bands_retained() {
        ScoreBand band = new ScoreBand(0, 60, "REJECT", "HIGH_RISK");
        ScorecardRootNode node = new ScorecardRootNode(List.of(), 60.0, List.of(band));
        assertEquals(List.of(band), node.bands());
    }

    /** 回归：record 有 2 参兼容构造，反序列化须由 @JsonCreator 锁定 3 参规范构造，否则漏读 bands（e2e 暴露）。 */
    @Test
    void jsonRoundTrip_preservesBands() {
        String json = """
            {"type":"ScorecardRootNode","threshold":0.0,"conditions":[],
             "bands":[{"minScore":0,"maxScore":60,"decisionCode":"REJECT","category":"HIGH"},
                      {"minScore":60,"maxScore":100,"decisionCode":"PASS","category":"LOW"}]}
            """;
        AstNode node = mapper.readValue(json, AstNode.class);
        assertInstanceOf(ScorecardRootNode.class, node);
        ScorecardRootNode sc = (ScorecardRootNode) node;
        assertEquals(2, sc.bands().size());
        assertEquals("REJECT", sc.bands().get(0).decisionCode());
        assertEquals("HIGH", sc.bands().get(0).category());
        assertEquals(60.0, sc.bands().get(1).minScore());
    }

    /** 序列化往返：写出再读回，bands 不丢。 */
    @Test
    void serializeThenDeserialize_bandsSurvive() {
        ScorecardRootNode src = new ScorecardRootNode(List.of(), 0.0,
                List.of(new ScoreBand(0, 50, "REVIEW", "MID")));
        String json = mapper.writeValueAsString(src);
        ScorecardRootNode back = (ScorecardRootNode) mapper.readValue(json, AstNode.class);
        assertEquals(1, back.bands().size());
        assertEquals("REVIEW", back.bands().get(0).decisionCode());
    }
}
