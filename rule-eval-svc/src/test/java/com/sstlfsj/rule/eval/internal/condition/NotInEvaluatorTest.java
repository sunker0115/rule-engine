package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NotInEvaluatorTest extends BaseEvaluatorTest {

    private final NotInEvaluator ev = new NotInEvaluator();

    private ConditionNode notIn(Object... values) {
        return new ConditionNode("NOT_IN", "m", null, Map.of("values", List.of(values)), 0.0);
    }

    @Test void valueNotInList_returnsTrue()    { assertTrue(ev.evaluate(notIn("A","B"), ctxWith("m","C"))); }
    @Test void valueInList_returnsFalse()      { assertFalse(ev.evaluate(notIn("A","B"), ctxWith("m","A"))); }
    @Test void numericNotInList_returnsTrue()  { assertTrue(ev.evaluate(notIn(1,2,3), ctxWith("m", 4))); }
    @Test void numericInList_returnsFalse()    { assertFalse(ev.evaluate(notIn(1,2,3), ctxWith("m", 2))); }
    @Test void metricMissing_returnsTrue()     { assertTrue(ev.evaluate(notIn("A"), emptyCtx())); }
    @Test void valuesParamMissing_returnsTrue() {
        ConditionNode n = new ConditionNode("NOT_IN", "m", null, Map.of(), 0.0);
        assertTrue(ev.evaluate(n, ctxWith("m", "A")));
    }
}
