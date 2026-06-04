package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NeqEvaluatorTest extends BaseEvaluatorTest {

    private final NeqEvaluator ev = new NeqEvaluator();

    @Test void numericNotEqual_returnsTrue()  { assertTrue(ev.evaluate(node("m","NEQ",10), ctxWith("m", 5))); }
    @Test void numericEqual_returnsFalse()    { assertFalse(ev.evaluate(node("m","NEQ",10), ctxWith("m", 10))); }
    @Test void stringNotEqual_returnsTrue()   { assertTrue(ev.evaluate(node("m","NEQ","A"), ctxWith("m","B"))); }
    @Test void stringEqual_returnsFalse()     { assertFalse(ev.evaluate(node("m","NEQ","A"), ctxWith("m","A"))); }
    @Test void metricMissing_returnsFalse()   { assertFalse(ev.evaluate(node("m","NEQ",1), emptyCtx())); }
    @Test void thresholdNull_returnsFalse()   {
        ConditionNode n = new ConditionNode("NEQ","m",null, Map.of(), 0.0);
        assertFalse(ev.evaluate(n, ctxWith("m",1)));
    }
}
