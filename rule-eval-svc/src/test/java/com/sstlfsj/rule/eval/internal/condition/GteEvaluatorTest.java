package com.sstlfsj.rule.eval.internal.condition;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GteEvaluatorTest extends BaseEvaluatorTest {

    private final GteEvaluator ev = new GteEvaluator();

    @Test void actualGreater_returnsTrue() { assertTrue(ev.evaluate(node("v","GTE",50), ctxWith("v",80))); }
    @Test void actualEqual_returnsTrue()   { assertTrue(ev.evaluate(node("v","GTE",80), ctxWith("v",80))); }
    @Test void actualLess_returnsFalse()   { assertFalse(ev.evaluate(node("v","GTE",100), ctxWith("v",80))); }
}
