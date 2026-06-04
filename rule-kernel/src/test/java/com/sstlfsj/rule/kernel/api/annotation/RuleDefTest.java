package com.sstlfsj.rule.kernel.api.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.*;

import static org.junit.jupiter.api.Assertions.*;

class RuleDefTest {

    @RuleDef(id = 1L, tenantId = "t1", sceneCode = "fraud",
             trigger = "TRANSACTION",
             decisions = @DecisionBinding(code = "BLOCK", priority = 100))
    static class FullRule {}

    @RuleDef(id = 2L, tenantId = "t1", sceneCode = "scene")
    static class MinimalRule {}

    @Test
    void allAttributes_areReadCorrectly() {
        RuleDef ann = FullRule.class.getAnnotation(RuleDef.class);
        assertEquals(1L, ann.id());
        assertEquals("t1", ann.tenantId());
        assertEquals("fraud", ann.sceneCode());
        assertArrayEquals(new String[]{"TRANSACTION"}, ann.trigger());
        assertEquals(1, ann.decisions().length);
        assertEquals("BLOCK", ann.decisions()[0].code());
        assertEquals(100, ann.decisions()[0].priority());
    }

    @Test
    void defaults_areApplied() {
        RuleDef ann = MinimalRule.class.getAnnotation(RuleDef.class);
        assertEquals(2L, ann.id());
        assertEquals(0, ann.trigger().length);
        assertEquals(0, ann.decisions().length);
    }

    @Test
    void retentionIsRuntime() {
        Retention retention = RuleDef.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void targetIsType() {
        Target target = RuleDef.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.TYPE, target.value()[0]);
    }

    @Test
    void isDocumented() {
        assertNotNull(RuleDef.class.getAnnotation(Documented.class));
    }
}
