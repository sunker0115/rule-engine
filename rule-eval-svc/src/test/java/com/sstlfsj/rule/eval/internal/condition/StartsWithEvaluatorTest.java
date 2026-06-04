package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StartsWithEvaluatorTest extends BaseEvaluatorTest {

    private final StartsWithEvaluator ev = new StartsWithEvaluator();

    private ConditionNode startsWith(String prefix) {
        return new ConditionNode("STARTS_WITH", "m", null, Map.of("prefix", prefix), 0.0);
    }

    @Test void matchingPrefix_returnsTrue()    { assertTrue(ev.evaluate(startsWith("foo"), ctxWith("m", "foobar"))); }
    @Test void exactMatch_returnsTrue()        { assertTrue(ev.evaluate(startsWith("foo"), ctxWith("m", "foo"))); }
    @Test void nonMatchingPrefix_returnsFalse(){ assertFalse(ev.evaluate(startsWith("bar"), ctxWith("m", "foobar"))); }
    @Test void metricMissing_returnsFalse()    { assertFalse(ev.evaluate(startsWith("foo"), emptyCtx())); }
    @Test void prefixParamMissing_returnsFalse() {
        ConditionNode n = new ConditionNode("STARTS_WITH", "m", null, Map.of(), 0.0);
        assertFalse(ev.evaluate(n, ctxWith("m", "foobar")));
    }
}
