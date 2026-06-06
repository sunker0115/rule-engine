package com.sstlfsj.rule.kernel.api.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.*;

class DecisionBindingTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Holder {
        DecisionBinding value();
    }

    @Holder(@DecisionBinding(code = "BLOCK", priority = 100))
    static class WithFull {}

    @Holder(@DecisionBinding(code = "PASS"))
    static class WithDefault {}

    @Test
    void code_isReadCorrectly() {
        DecisionBinding ann = WithFull.class.getAnnotation(Holder.class).value();
        assertEquals("BLOCK", ann.code());
        assertEquals(100, ann.priority());
    }

    @Test
    void priority_defaultIsZero() {
        DecisionBinding ann = WithDefault.class.getAnnotation(Holder.class).value();
        assertEquals("PASS", ann.code());
        assertEquals(0, ann.priority());
    }

    @Test
    void retentionIsRuntime() {
        Retention retention = DecisionBinding.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }
}
