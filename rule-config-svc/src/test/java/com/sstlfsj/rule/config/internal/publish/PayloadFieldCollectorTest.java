package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 PayloadFieldCollector 仅收集 valueRef=PAYLOAD 的字段。 */
class PayloadFieldCollectorTest {

    @Test
    void collectsOnlyPayloadRefFields() {
        AstNode ast = new AndNode(java.util.List.of(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0, null, ValueRef.PAYLOAD),
                new ConditionNode("GTE", "user.risk.score", null, Map.of("threshold", 80), 0.0, null, ValueRef.METRIC)
        ), null, null);
        assertThat(PayloadFieldCollector.collect(ast)).containsExactly("amount");
    }
}
