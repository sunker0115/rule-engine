package com.sstlfsj.rule.eval.internal.condition;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LtEvaluatorTest extends BaseEvaluatorTest {

    private final LtEvaluator ev = new LtEvaluator();

    @Test void actualLess_returnsTrue()    { assertTrue(ev.evaluate(node("v","LT",100), ctxWith("v",80))); }
    @Test void actualEqual_returnsFalse()  { assertFalse(ev.evaluate(node("v","LT",80), ctxWith("v",80))); }
    @Test void actualGreater_returnsFalse(){ assertFalse(ev.evaluate(node("v","LT",50), ctxWith("v",80))); }
    @Test void metricMissing_returnsFalse(){ assertFalse(ev.evaluate(node("v","LT",100), emptyCtx())); }
}
