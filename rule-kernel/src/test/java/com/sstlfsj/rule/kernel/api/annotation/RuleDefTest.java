package com.sstlfsj.rule.kernel.api.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.*;

import static org.junit.jupiter.api.Assertions.*;

class RuleDefTest {

    @RuleDef(code = "block-large", tenantId = "t1", sceneCode = "fraud",
             version = 3L,
             eventTypes = "TRANSACTION",
             decisions = @DecisionBinding(code = "BLOCK", priority = 100))
    static class FullRule {}

    @RuleDef(code = "minimal")
    static class MinimalRule {}

    @Test
    void allAttributes_areReadCorrectly() {
        RuleDef ann = FullRule.class.getAnnotation(RuleDef.class);
        assertEquals("block-large", ann.code());
        assertEquals("t1", ann.tenantId());
        assertEquals("fraud", ann.sceneCode());
        assertEquals(3L, ann.version());
        assertArrayEquals(new String[]{"TRANSACTION"}, ann.eventTypes());
        assertEquals(1, ann.decisions().length);
        assertEquals("BLOCK", ann.decisions()[0].code());
        assertEquals(100, ann.decisions()[0].priority());
    }

    @Test
    void defaults_areApplied() {
        RuleDef ann = MinimalRule.class.getAnnotation(RuleDef.class);
        assertEquals("minimal", ann.code());
        assertEquals("", ann.tenantId());
        assertEquals("default", ann.sceneCode());          // 缺省 = DEFAULT_SCENE
        assertEquals(RuleDef.DEFAULT_SCENE, ann.sceneCode());
        assertEquals(1L, ann.version());
        assertEquals(0, ann.eventTypes().length);
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
