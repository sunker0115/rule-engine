package com.sstlfsj.rule.kernel.api.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeTraceFormatterTest {

    private static NodeTrace leaf(String conditionType, String metricCode, Boolean result,
                                  String errorCode) {
        return new NodeTrace(NodeType.CONDITION.tag(), conditionType, metricCode, result,
                null, null, errorCode, List.of(), null, null, 0L, null, null);
    }

    @Test
    void emptyOrNull_returnsBracketPlaceholder() {
        assertEquals("[]", NodeTraceFormatter.compact(List.of()));
        assertEquals("[]", NodeTraceFormatter.compact(null));
    }

    @Test
    void leafCondition_rendersTypeMetricAndResult() {
        String s = NodeTraceFormatter.compact(List.of(leaf("GT", "order_amount", true, null)));
        assertEquals("GT:order_amount:T", s);
    }

    @Test
    void leafWithoutMetric_omitsMetricSegment() {
        String s = NodeTraceFormatter.compact(List.of(leaf("EQ", null, false, null)));
        assertEquals("EQ:F", s);
    }

    @Test
    void errorNode_marksEWithCode() {
        String s = NodeTraceFormatter.compact(List.of(leaf("GT", "amt", false, "NO_EVALUATOR")));
        assertEquals("GT:amt:E:NO_EVALUATOR", s);
    }

    @Test
    void container_nestsChildrenAndShortensType() {
        NodeTrace and = NodeTrace.container(NodeType.AND, true,
                List.of(leaf("GT", "order_amount", true, null),
                        leaf("IN", "user_level", true, null)),
                1L, "PROMO_A", 3L);
        String s = NodeTraceFormatter.compact(List.of(and));
        assertEquals("PROMO_A#v3=And:T[GT:order_amount:T,IN:user_level:T]", s);
    }

    @Test
    void nullResultContainer_marksDash() {
        NodeTrace container = NodeTrace.container(NodeType.OR, null, List.of(), null);
        String s = NodeTraceFormatter.compact(List.of(container));
        assertEquals("Or:-", s);
    }

    @Test
    void missingRuleCode_fallsBackToRvPrefix() {
        // ruleCode 为 null 但有 ruleVersionId 时回退 rv<id> 前缀
        NodeTrace and = NodeTrace.container(NodeType.AND, true,
                List.of(leaf("GT", "amt", true, null)), 7L);
        String s = NodeTraceFormatter.compact(List.of(and));
        assertEquals("rv7=And:T[GT:amt:T]", s);
    }

    @Test
    void multipleTrees_spaceSeparated() {
        NodeTrace a = NodeTrace.container(NodeType.AND, true, List.of(), 1L, "A", 1L);
        NodeTrace b = NodeTrace.container(NodeType.OR, false, List.of(), 2L, "B", 2L);
        String s = NodeTraceFormatter.compact(List.of(a, b));
        assertEquals("A#v1=And:T B#v2=Or:F", s);
    }
}
