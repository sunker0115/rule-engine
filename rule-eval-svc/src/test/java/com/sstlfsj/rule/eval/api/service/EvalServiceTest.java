package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 EvalService 是公开接口且具有预期的方法签名。 */
class EvalServiceTest {

    @Test
    void isInterface() {
        assertTrue(EvalService.class.isInterface());
    }

    @Test
    void hasAcceptEventMethod() throws NoSuchMethodException {
        var method = EvalService.class.getMethod("acceptEvent", RuleEvent.class);
        assertEquals(boolean.class, method.getReturnType());
    }

    @Test
    void hasEvaluateMethod() throws NoSuchMethodException {
        var method = EvalService.class.getMethod("evaluate", RuleEvent.class);
        assertEquals(EvalResult.class, method.getReturnType());
    }

    @Test
    void hasDryRunMethod() throws NoSuchMethodException {
        // dry-run 二选一必传：签名为 (event, ruleId, ruleVersionId)
        var method = EvalService.class.getMethod("dryRun", RuleEvent.class, Long.class, Long.class);
        assertEquals(EvalResult.class, method.getReturnType());
    }
}
