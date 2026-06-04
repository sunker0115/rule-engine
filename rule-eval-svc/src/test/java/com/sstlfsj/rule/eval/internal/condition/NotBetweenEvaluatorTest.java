package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NotBetweenEvaluatorTest extends BaseEvaluatorTest {

    private final NotBetweenEvaluator ev = new NotBetweenEvaluator();

    private ConditionNode notBetween(Object min, Object max) {
        return new ConditionNode("NOT_BETWEEN", "m", null, Map.of("min", min, "max", max), 0.0);
    }

    @Test void valueBelowMin_returnsTrue()      { assertTrue(ev.evaluate(notBetween(5, 10), ctxWith("m", 4))); }
    @Test void valueAboveMax_returnsTrue()      { assertTrue(ev.evaluate(notBetween(1, 5), ctxWith("m", 6))); }
    @Test void valueInRange_returnsFalse()      { assertFalse(ev.evaluate(notBetween(1, 10), ctxWith("m", 5))); }
    @Test void valueAtMin_returnsFalse()        { assertFalse(ev.evaluate(notBetween(5, 10), ctxWith("m", 5))); }
    @Test void valueAtMax_returnsFalse()        { assertFalse(ev.evaluate(notBetween(1, 5), ctxWith("m", 5))); }
    @Test void metricMissing_returnsFalse()     { assertFalse(ev.evaluate(notBetween(1, 10), emptyCtx())); }
    @Test void maxNull_returnsFalse() {
        ConditionNode n = new ConditionNode("NOT_BETWEEN", "m", null, Map.of("min", 1), 0.0);
        assertFalse(ev.evaluate(n, ctxWith("m", 5)));
    }
}
