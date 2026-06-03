package com.sstlfsj.rule.eval.internal.condition;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GtEvaluatorTest extends BaseEvaluatorTest {

    private final GtEvaluator ev = new GtEvaluator();

    @Test void actualGreater_returnsTrue()  { assertTrue(ev.evaluate(node("v","GT",50), ctxWith("v",80))); }
    @Test void actualEqual_returnsFalse()   { assertFalse(ev.evaluate(node("v","GT",80), ctxWith("v",80))); }
    @Test void actualLess_returnsFalse()    { assertFalse(ev.evaluate(node("v","GT",100), ctxWith("v",80))); }
    @Test void metricMissing_returnsFalse() { assertFalse(ev.evaluate(node("v","GT",50), emptyCtx())); }
    @Test void stringValue_parsed()        { assertTrue(ev.evaluate(node("v","GT",50), ctxWith("v","80"))); }
}
