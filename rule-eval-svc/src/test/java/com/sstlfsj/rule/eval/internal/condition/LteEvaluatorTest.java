package com.sstlfsj.rule.eval.internal.condition;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LteEvaluatorTest extends BaseEvaluatorTest {

    private final LteEvaluator ev = new LteEvaluator();

    @Test void actualLess_returnsTrue()    { assertTrue(ev.evaluate(node("v","LTE",100), ctxWith("v",80))); }
    @Test void actualEqual_returnsTrue()   { assertTrue(ev.evaluate(node("v","LTE",80), ctxWith("v",80))); }
    @Test void actualGreater_returnsFalse(){ assertFalse(ev.evaluate(node("v","LTE",50), ctxWith("v",80))); }
}
