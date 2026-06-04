package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EndsWithEvaluatorTest extends BaseEvaluatorTest {

    private final EndsWithEvaluator ev = new EndsWithEvaluator();

    private ConditionNode endsWith(String suffix) {
        return new ConditionNode("ENDS_WITH", "m", null, Map.of("suffix", suffix), 0.0);
    }

    @Test void matchingSuffix_returnsTrue()    { assertTrue(ev.evaluate(endsWith("bar"), ctxWith("m", "foobar"))); }
    @Test void exactMatch_returnsTrue()        { assertTrue(ev.evaluate(endsWith("foo"), ctxWith("m", "foo"))); }
    @Test void nonMatchingSuffix_returnsFalse(){ assertFalse(ev.evaluate(endsWith("foo"), ctxWith("m", "foobar"))); }
    @Test void metricMissing_returnsFalse()    { assertFalse(ev.evaluate(endsWith("bar"), emptyCtx())); }
    @Test void suffixParamMissing_returnsFalse() {
        ConditionNode n = new ConditionNode("ENDS_WITH", "m", null, Map.of(), 0.0);
        assertFalse(ev.evaluate(n, ctxWith("m", "foobar")));
    }
}
