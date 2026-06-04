package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InEvaluatorTest extends BaseEvaluatorTest {

    private final InEvaluator ev = new InEvaluator();

    private ConditionNode inNode(String metricCode, Object... values) {
        return new ConditionNode("IN", metricCode, null, Map.of("values", List.of(values)), 0.0);
    }

    @Test void valuePresent_returnsTrue()  { assertTrue(ev.evaluate(inNode("c","CN","US"), ctxWith("c","CN"))); }
    @Test void valueAbsent_returnsFalse()  { assertFalse(ev.evaluate(inNode("c","CN","US"), ctxWith("c","JP"))); }
    @Test void noValuesParam_returnsFalse(){
        ConditionNode n = new ConditionNode("IN","c",null, Map.of(), 0.0);
        assertFalse(ev.evaluate(n, ctxWith("c","CN")));
    }
    @Test void metricMissing_returnsFalse(){ assertFalse(ev.evaluate(inNode("c","CN"), emptyCtx())); }
}
