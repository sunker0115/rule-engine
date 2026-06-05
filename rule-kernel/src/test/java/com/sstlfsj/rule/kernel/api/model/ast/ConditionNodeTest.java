package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionNodeTest {

    @Test
    void params_areImmutable() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        ConditionNode node = new ConditionNode("T", "m", null, mutable, 0.0);
        mutable.put("extra", "x");
        assertEquals(1, node.params().size(), "构造后修改原始 map 不应影响 ConditionNode");
    }

    @Test
    void params_mapIsUnmodifiable() {
        ConditionNode node = new ConditionNode("T", "m", null, Map.of(), 0.0);
        assertThrows(UnsupportedOperationException.class,
                () -> node.params().put("k", "v"));
    }

    @Test
    void recordEquality_byValue() {
        ConditionNode a = new ConditionNode("AMOUNT_GT", "balance", "余额大于", Map.of("threshold", 100), 0.3);
        ConditionNode b = new ConditionNode("AMOUNT_GT", "balance", "余额大于", Map.of("threshold", 100), 0.3);
        assertEquals(a, b);
    }

    @Test
    void nullableDisplayLabel_allowsNull() {
        ConditionNode node = new ConditionNode("T", "m", null, Map.of(), 0.0);
        assertNull(node.displayLabel());
    }

    @Test
    void weight_defaultsToZero_whenNotScorecard() {
        ConditionNode node = new ConditionNode("GT", "score", null, Map.of(), 0.0);
        assertEquals(0.0, node.weight());
    }

    @Test
    void weight_retainsSpecifiedValue() {
        ConditionNode node = new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4);
        assertEquals(0.4, node.weight(), 1e-9);
    }

    @Test
    void weight_allowsNull_forAstBooleanKind() {
        // weight 改为 Double 后，AST_BOOLEAN kind 场景可传 null，反序列化时缺失字段不报错
        ConditionNode node = new ConditionNode("GT", "score", null, Map.of(), null);
        assertNull(node.weight());
    }
}
