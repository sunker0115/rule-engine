package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionPayloadTest {

    @Test
    void payloadGt_buildsPayloadRefNode() {
        ConditionNode n = (ConditionNode) Condition.payloadGt("amount", 1000).toAst();
        assertEquals(ValueRef.PAYLOAD, n.valueRef());
        assertEquals("GT", n.conditionType());
        assertEquals("amount", n.metricCode());
        assertEquals(1000, n.params().get("threshold"));
    }

    @Test
    void payloadEq_usesThresholdParamKey() {
        // 键须为 "threshold"(EqEvaluator 消费端)；旧用例断言 "value" 固化了"永不命中"的 bug
        ConditionNode n = (ConditionNode) Condition.payloadEq("channel", "APP").toAst();
        assertEquals(ValueRef.PAYLOAD, n.valueRef());
        assertEquals("EQ", n.conditionType());
        assertEquals("APP", n.params().get("threshold"));
    }

    @Test
    void payloadIn_usesValuesParamKey() {
        ConditionNode n = (ConditionNode) Condition.payloadIn("city", "BJ", "SH").toAst();
        assertEquals(ValueRef.PAYLOAD, n.valueRef());
        assertEquals("IN", n.conditionType());
        assertEquals(List.of("BJ", "SH"), n.params().get("values"));
    }

    @Test
    void payloadBetween_usesMinMaxParamKeys() {
        ConditionNode n = (ConditionNode) Condition.payloadBetween("amount", 10, 100).toAst();
        assertEquals(ValueRef.PAYLOAD, n.valueRef());
        assertEquals("BETWEEN", n.conditionType());
        assertEquals(10, n.params().get("min"));
        assertEquals(100, n.params().get("max"));
    }

    @Test
    void gt_staysMetricRef() {
        ConditionNode n = (ConditionNode) Condition.gt("user.risk.score", 80).toAst();
        assertEquals(ValueRef.METRIC, n.valueRef());
    }
}
