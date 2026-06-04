package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EqEvaluatorTest extends BaseEvaluatorTest {

    private final EqEvaluator ev = new EqEvaluator();

    @Test void numericEqual_returnsTrue()    { assertTrue(ev.evaluate(node("v","EQ",3), ctxWith("v",3))); }
    @Test void numericNotEqual_returnsFalse(){ assertFalse(ev.evaluate(node("v","EQ",3), ctxWith("v",5))); }
    @Test void stringEqual_returnsTrue() {
        ConditionNode n = new ConditionNode("EQ","ch",null, Map.of("threshold","ONLINE"), 0.0);
        assertTrue(ev.evaluate(n, ctxWith("ch","ONLINE")));
    }
    @Test void stringNotEqual_returnsFalse() {
        ConditionNode n = new ConditionNode("EQ","ch",null, Map.of("threshold","ONLINE"), 0.0);
        assertFalse(ev.evaluate(n, ctxWith("ch","OFFLINE")));
    }
    @Test void metricMissing_returnsFalse(){ assertFalse(ev.evaluate(node("v","EQ",3), emptyCtx())); }
}
