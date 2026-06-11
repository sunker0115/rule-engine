package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AstDataTypeResolverPayloadTest {
    @Test
    void payloadNode_getsDataTypeFromPayloadMap_andKeepsPayloadRef() {
        ConditionNode src = new ConditionNode("GT", "amount", null,
                Map.of("threshold", 1000), 0.0, null, ValueRef.PAYLOAD);
        AstNode out = AstDataTypeResolver.resolve(src, Map.of(), Map.of("amount", "DECIMAL"));
        ConditionNode r = (ConditionNode) out;
        assertEquals("DECIMAL", r.dataType());
        assertEquals(ValueRef.PAYLOAD, r.valueRef());
    }

    @Test
    void metricNode_unaffectedByPayloadMap() {
        ConditionNode src = new ConditionNode("GT", "user.risk.score", null,
                Map.of("threshold", 80), 0.0, null, ValueRef.METRIC);
        AstNode out = AstDataTypeResolver.resolve(src, Map.of("user.risk.score", "LONG"), Map.of());
        ConditionNode r = (ConditionNode) out;
        assertEquals("LONG", r.dataType());
        assertEquals(ValueRef.METRIC, r.valueRef());
    }
}
