package com.sstlfsj.rule.kernel.spi.expression;

import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ScriptTypeEnv;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;

class ExpressionEngineTest {

    /** 无 typeCheck override 的弱引擎:默认 no-op,不抛异常。 */
    private static final class WeakEngine implements ExpressionEngine {
        public String lang() { return "WEAK"; }
        public CompiledExpression compile(String source) { return Set::of; }
        public Object evaluate(CompiledExpression c, Map<String, Object> b) { return null; }
    }

    @Test
    void defaultTypeCheckIsNoOp() {
        ExpressionEngine engine = new WeakEngine();
        assertThatCode(() -> engine.typeCheck("anything", new ScriptTypeEnv(Map.of(), Map.of())))
                .doesNotThrowAnyException();
    }
}
