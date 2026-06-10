package com.sstlfsj.rule.kernel.api.model.ast;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionNodeValueRefTest {
    @Test
    void fiveArgConstructor_defaultsToMetric() {
        ConditionNode n = new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0);
        assertEquals(ValueRef.METRIC, n.valueRef());
    }

    @Test
    void nullValueRef_coercedToMetric() {
        ConditionNode n = new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0, "LONG", null);
        assertEquals(ValueRef.METRIC, n.valueRef());
    }

    @Test
    void payloadValueRef_preserved() {
        ConditionNode n = new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0, "DECIMAL", ValueRef.PAYLOAD);
        assertEquals(ValueRef.PAYLOAD, n.valueRef());
    }
}
