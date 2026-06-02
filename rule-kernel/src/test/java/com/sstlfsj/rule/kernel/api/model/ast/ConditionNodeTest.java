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
        ConditionNode node = new ConditionNode("T", "m", null, mutable);
        mutable.put("extra", "x");
        assertEquals(1, node.params().size(), "构造后修改原始 map 不应影响 ConditionNode");
    }

    @Test
    void params_mapIsUnmodifiable() {
        ConditionNode node = new ConditionNode("T", "m", null, Map.of());
        assertThrows(UnsupportedOperationException.class,
                () -> node.params().put("k", "v"));
    }

    @Test
    void recordEquality_byValue() {
        ConditionNode a = new ConditionNode("AMOUNT_GT", "balance", "余额大于", Map.of("threshold", 100));
        ConditionNode b = new ConditionNode("AMOUNT_GT", "balance", "余额大于", Map.of("threshold", 100));
        assertEquals(a, b);
    }

    @Test
    void nullableDisplayLabel_allowsNull() {
        ConditionNode node = new ConditionNode("T", "m", null, Map.of());
        assertNull(node.displayLabel());
    }
}
