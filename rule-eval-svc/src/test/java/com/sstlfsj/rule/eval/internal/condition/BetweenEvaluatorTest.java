package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BetweenEvaluatorTest extends BaseEvaluatorTest {

    private final BetweenEvaluator ev = new BetweenEvaluator();

    private ConditionNode between(Object min, Object max) {
        return new ConditionNode("BETWEEN", "m", null, Map.of("min", min, "max", max), 0.0);
    }

    @Test void valueInRange_returnsTrue()       { assertTrue(ev.evaluate(between(1, 10), ctxWith("m", 5))); }
    @Test void valueAtMin_returnsTrue()          { assertTrue(ev.evaluate(between(5, 10), ctxWith("m", 5))); }
    @Test void valueAtMax_returnsTrue()          { assertTrue(ev.evaluate(between(1, 5), ctxWith("m", 5))); }
    @Test void valueBelowMin_returnsFalse()      { assertFalse(ev.evaluate(between(5, 10), ctxWith("m", 4))); }
    @Test void valueAboveMax_returnsFalse()      { assertFalse(ev.evaluate(between(1, 5), ctxWith("m", 6))); }
    @Test void metricMissing_returnsFalse()      { assertFalse(ev.evaluate(between(1, 10), emptyCtx())); }
    @Test void minNull_returnsFalse() {
        ConditionNode n = new ConditionNode("BETWEEN", "m", null, Map.of("max", 10), 0.0);
        assertFalse(ev.evaluate(n, ctxWith("m", 5)));
    }
}
