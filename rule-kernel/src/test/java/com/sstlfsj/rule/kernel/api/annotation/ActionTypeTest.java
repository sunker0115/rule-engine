package com.sstlfsj.rule.kernel.api.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.*;

class ActionTypeTest {

    @ActionType("SEND_MSG")
    static class MinimalHandler {}

    @ActionType(value = "FREEZE_ACCOUNT", displayName = "冻结账户",
                paramsSchema = "{}", timeoutMs = 5000, compensatable = true)
    static class FullHandler {}

    @Test
    void annotation_isPresentAtRuntime() {
        assertNotNull(MinimalHandler.class.getAnnotation(ActionType.class));
    }

    @Test
    void value_isReadCorrectly() {
        ActionType ann = MinimalHandler.class.getAnnotation(ActionType.class);
        assertEquals("SEND_MSG", ann.value());
    }

    @Test
    void defaults_areApplied() {
        ActionType ann = MinimalHandler.class.getAnnotation(ActionType.class);
        assertEquals("", ann.displayName());
        assertEquals("{}", ann.paramsSchema());
        assertEquals(3000, ann.timeoutMs());
        assertFalse(ann.compensatable());
    }

    @Test
    void allAttributes_areReadCorrectly() {
        ActionType ann = FullHandler.class.getAnnotation(ActionType.class);
        assertEquals("FREEZE_ACCOUNT", ann.value());
        assertEquals("冻结账户", ann.displayName());
        assertEquals(5000, ann.timeoutMs());
        assertTrue(ann.compensatable());
    }

    @Test
    void retentionIsRuntime() {
        Retention retention = ActionType.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void targetIsType() {
        Target target = ActionType.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertEquals(ElementType.TYPE, target.value()[0]);
    }
}
