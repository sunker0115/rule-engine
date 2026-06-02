package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies EvalServiceImpl stub methods throw UnsupportedOperationException. */
class EvalServiceImplTest {

    private final EvalServiceImpl impl = new EvalServiceImpl();

    @Test
    void acceptEvent_throwsUnsupportedOperation() {
        assertThrows(UnsupportedOperationException.class,
                () -> impl.acceptEvent(null));
    }

    @Test
    void evaluate_throwsUnsupportedOperation() {
        assertThrows(UnsupportedOperationException.class,
                () -> impl.evaluate(null));
    }

    @Test
    void dryRun_throwsUnsupportedOperation() {
        assertThrows(UnsupportedOperationException.class,
                () -> impl.dryRun(null, null));
    }
}
