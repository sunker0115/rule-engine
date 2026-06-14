package com.sstlfsj.rule.expression.aviator;

import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEvaluateException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AviatorExpressionEngineTest {

    private final AviatorExpressionEngine engine = new AviatorExpressionEngine();

    private static Map<String, Object> bindings(Map<String, Object> metrics, Map<String, Object> payload) {
        Map<String, Object> b = new HashMap<>();
        b.put("metrics", metrics);
        b.put("payload", payload);
        b.put("subject", Map.of());
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        return b;
    }

    @Test
    void langIsAviator() {
        assertThat(engine.lang()).isEqualTo(ExpressionLang.AVIATOR.tag());
    }

    @Test
    void evaluatesStringDecision() {
        CompiledExpression c = engine.compile("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        Object out = engine.evaluate(c, bindings(Map.of(), Map.of("amount", 12000L)));
        assertThat(out).isEqualTo("REVIEW");
    }

    @Test
    void evaluatesJsonSourcedNumericTypes() {
        // Aviator 弱类型:Integer/Double/BigDecimal/Float 自动提升,无需手动规整
        CompiledExpression gt = engine.compile("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", 12000)))).isEqualTo("REVIEW");             // Integer
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", 5000)))).isEqualTo("PASS");               // Integer
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", new java.math.BigDecimal("12000"))))).isEqualTo("REVIEW"); // BigDecimal
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", 12000.0)))).isEqualTo("REVIEW");          // Double

        // Float 数值比较
        assertThat(engine.evaluate(engine.compile("metrics.score > 50.0"),
                bindings(Map.of("score", 72.5f), Map.of()))).isEqualTo(true);
    }

    @Test
    void evaluatesBooleanAndNumber() {
        assertThat(engine.evaluate(engine.compile("metrics.cnt > 50"),
                bindings(Map.of("cnt", 53L), Map.of()))).isEqualTo(true);
        assertThat(engine.evaluate(engine.compile("metrics.cnt > 50"),
                bindings(Map.of("cnt", 40L), Map.of()))).isEqualTo(false);
        assertThat(engine.evaluate(engine.compile("metrics.score + 0.5"),
                bindings(Map.of("score", 10.0), Map.of()))).isEqualTo(10.5);
    }

    @Test
    void evaluatesNowAsEpochMillis() {
        // now(Instant)经 adaptBindings 转 epoch millis long,可参与数值比较
        CompiledExpression c = engine.compile("now > 1577836800000"); // 2020-01-01T00:00:00Z
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of()))).isEqualTo(true);
    }

    @Test
    void compileCachesBySource() {
        // 同源脚本命中 Caffeine 缓存,返回同一实例
        CompiledExpression a = engine.compile("payload.x > 1");
        CompiledExpression b = engine.compile("payload.x > 1");
        assertThat(a).isSameAs(b);
    }

    @Test
    void exposesReferencedVariables() {
        CompiledExpression c = engine.compile("metrics.txn_cnt_1d > 50 && payload.amount > 0");
        assertThat(c.referencedVariables()).contains("metrics.txn_cnt_1d", "payload.amount");
    }

    @Test
    void syntaxErrorThrowsCompileException() {
        assertThatThrownBy(() -> engine.compile("metrics.x >>>> "))
                .isInstanceOf(ExpressionCompileException.class);
    }

    @Test
    void safeByDesignNoFileOrReflection() {
        // Aviator 无文件/反射/类加载内建;Java 路径经变量绑定模型隔离——
        // 编译通过(被解析为变量引用链),求值时不在 bindings 中 → 失败
        CompiledExpression c = engine.compile("java.lang.Runtime.getRuntime()");
        assertThatThrownBy(() -> engine.evaluate(c, bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void nullResultOnNoMatch() {
        // nil 与非 nil 值比较 → false,Aviator 的 nil == Aviator 的 null
        CompiledExpression c = engine.compile("nil == 1 ? 'only_if_true' : nil");
        Object out = engine.evaluate(c, bindings(Map.of(), Map.of()));
        assertThat(out).isNull();
    }

    @Test
    void subjectVariableAccessible() {
        Map<String, Object> b = new HashMap<>();
        b.put("metrics", Map.of());
        b.put("payload", Map.of());
        b.put("subject", Map.of("level", "VIP"));
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        CompiledExpression c = engine.compile("subject.level == 'VIP'");
        assertThat(engine.evaluate(c, b)).isEqualTo(true);
    }

    @Test
    void evaluateFailureThrowsEvaluateException() {
        // 对非 String 值调用 .length() 方法——Aviator 会抛异常(方法不存在)
        CompiledExpression c = engine.compile("payload.name.length() > 3");
        assertThatThrownBy(() -> engine.evaluate(c, bindings(Map.of(), Map.of("name", 123))))
                .isInstanceOf(ExpressionEvaluateException.class);
    }
}
