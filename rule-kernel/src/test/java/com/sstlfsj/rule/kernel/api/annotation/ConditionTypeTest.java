package com.sstlfsj.rule.kernel.api.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.*;

class ConditionTypeTest {

    @ConditionType("AMOUNT_GT")
    static class MinimalHandler {}

    @ConditionType(value = "AGE_LT", displayName = "年龄小于", paramsSchema = "{}")
    static class FullHandler {}

    @Test
    void annotation_isPresentAtRuntime() {
        assertNotNull(MinimalHandler.class.getAnnotation(ConditionType.class));
    }

    @Test
    void value_isReadCorrectly() {
        ConditionType ann = MinimalHandler.class.getAnnotation(ConditionType.class);
        assertEquals("AMOUNT_GT", ann.value());
    }

    @Test
    void defaults_areApplied() {
        ConditionType ann = MinimalHandler.class.getAnnotation(ConditionType.class);
        assertEquals("", ann.displayName());
        assertEquals("{}", ann.paramsSchema());
    }

    @Test
    void allAttributes_areReadCorrectly() {
        ConditionType ann = FullHandler.class.getAnnotation(ConditionType.class);
        assertEquals("AGE_LT", ann.value());
        assertEquals("年龄小于", ann.displayName());
        assertEquals("{}", ann.paramsSchema());
    }

    @Test
    void retentionIsRuntime() {
        Retention retention = ConditionType.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void targetIsType() {
        Target target = ConditionType.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.TYPE, target.value()[0]);
    }

    @Test
    void isDocumentedAnnotationType() {
        assertTrue(ConditionType.class.isAnnotation());
        assertNotNull(ConditionType.class.getAnnotation(java.lang.annotation.Documented.class));
    }
}
