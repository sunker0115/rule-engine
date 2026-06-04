package com.sstlfsj.rule.kernel.api.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.*;

class MetricSourceTypeTest {

    @MetricSourceType("ACCOUNT_BALANCE")
    static class MinimalSource {}

    @MetricSourceType(value = "RISK_SCORE", paramsSchema = "{}")
    static class FullSource {}

    @Test
    void annotation_isPresentAtRuntime() {
        assertNotNull(MinimalSource.class.getAnnotation(MetricSourceType.class));
    }

    @Test
    void value_isReadCorrectly() {
        MetricSourceType ann = MinimalSource.class.getAnnotation(MetricSourceType.class);
        assertEquals("ACCOUNT_BALANCE", ann.value());
    }

    @Test
    void defaults_areApplied() {
        MetricSourceType ann = MinimalSource.class.getAnnotation(MetricSourceType.class);
        assertEquals("{}", ann.paramsSchema());
    }

    @Test
    void allAttributes_areReadCorrectly() {
        MetricSourceType ann = FullSource.class.getAnnotation(MetricSourceType.class);
        assertEquals("RISK_SCORE", ann.value());
        assertEquals("{}", ann.paramsSchema());
    }

    @Test
    void retentionIsRuntime() {
        Retention retention = MetricSourceType.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void targetIsType() {
        Target target = MetricSourceType.class.getAnnotation(Target.class);
        assertEquals(ElementType.TYPE, target.value()[0]);
    }

    @Test
    void isDocumentedAnnotationType() {
        assertTrue(MetricSourceType.class.isAnnotation());
        assertNotNull(MetricSourceType.class.getAnnotation(java.lang.annotation.Documented.class));
    }
}
