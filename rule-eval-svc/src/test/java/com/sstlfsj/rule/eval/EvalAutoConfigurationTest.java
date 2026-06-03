package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 EvalAutoConfiguration 带有必要的 Spring 注解，以及 Bean 工厂方法行为。 */
class EvalAutoConfigurationTest {

    private final EvalAutoConfiguration config = new EvalAutoConfiguration();

    @Test
    void hasAutoConfigurationAnnotation() {
        assertNotNull(EvalAutoConfiguration.class.getAnnotation(AutoConfiguration.class));
    }

    @Test
    void componentScanTargetsInternalPackage() {
        ComponentScan scan = EvalAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertNotNull(scan);
        assertArrayEquals(new String[]{"com.sstlfsj.rule.eval.internal"}, scan.value());
    }

    @Test
    void ruleVersionExecutor_nullEvaluators_returnsInterpretedExecutor() {
        // conditionEvaluators 为 null（无注册实现时 Spring 传入 null）
        RuleVersionExecutor executor = config.ruleVersionExecutor(null);
        assertNotNull(executor);
        assertInstanceOf(InterpretedExecutor.class, executor);
    }

    @Test
    void ruleVersionExecutor_emptyEvaluators_returnsInterpretedExecutor() {
        RuleVersionExecutor executor = config.ruleVersionExecutor(java.util.Map.of());
        assertNotNull(executor);
        assertInstanceOf(InterpretedExecutor.class, executor);
    }
}
