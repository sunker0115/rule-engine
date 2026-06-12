package com.sstlfsj.rule.expression.jexl;

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

class JexlExpressionEngineTest {

    private final JexlExpressionEngine engine = new JexlExpressionEngine();

    private static Map<String, Object> bindings(Map<String, Object> metrics, Map<String, Object> payload) {
        Map<String, Object> b = new HashMap<>();
        b.put("metrics", metrics);
        b.put("payload", payload);
        b.put("subject", Map.of());
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        return b;
    }

    @Test
    void langIsJexl() {
        assertThat(engine.lang()).isEqualTo(ExpressionLang.JEXL.tag());
    }

    @Test
    void evaluatesStringDecision() {
        CompiledExpression c = engine.compile("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        Object out = engine.evaluate(c, bindings(Map.of(), Map.of("amount", 12000L)));
        assertThat(out).isEqualTo("REVIEW");
    }

    @Test
    void evaluatesJsonSourcedNumericTypes() {
        // JEXL 弱类型:Integer/Double/BigDecimal 自动提升,无需手动规整
        CompiledExpression gt = engine.compile("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", 12000)))).isEqualTo("REVIEW");             // Integer
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", 5000)))).isEqualTo("PASS");                // Integer
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", new java.math.BigDecimal("12000"))))).isEqualTo("REVIEW"); // BigDecimal
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", 12000.0)))).isEqualTo("REVIEW");           // Double

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
    void safeByDesignNoRuntimeExecution() {
        // RESTRICTED 沙箱下 java.lang.Runtime 不可达:类引用求值为 null,exec 命令不会被执行
        CompiledExpression c = engine.compile("java.lang.Runtime.getRuntime().exec('id')");
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of()))).isNull();
    }

    @Test
    void safeByDesignNoReflectionEscape() {
        // 经 getClass() 拿到 Class 后无法 forName(Class 方法不暴露),反射逃逸被切断:求值为 null
        CompiledExpression c = engine.compile("''.getClass().forName('java.lang.Runtime')");
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of()))).isNull();
    }

    @Test
    void safeByDesignNoNewFile() {
        // RESTRICTED 沙箱禁止 java.io.File 构造器,求值期抛"方法不可解析"异常
        CompiledExpression c = engine.compile("new('java.io.File', '/etc/passwd').exists()");
        assertThatThrownBy(() -> engine.evaluate(c, bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void safeByDesignNoClassLoaderAccess() {
        // ''.class.classLoader 属性不可达,求值期抛异常,无法经类加载器逃逸
        CompiledExpression c = engine.compile("''.class.classLoader");
        assertThatThrownBy(() -> engine.evaluate(c, bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
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
        // 对 Integer 调用不存在的 length() 方法——JEXL 求值期抛异常
        CompiledExpression c = engine.compile("payload.name.length() > 3");
        assertThatThrownBy(() -> engine.evaluate(c, bindings(Map.of(), Map.of("name", 123))))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void varPathsFiltersNonNamespacePaths() {
        // 只收集 metrics./payload./subject. 路径,忽略纯变量名(如 now)
        CompiledExpression c = engine.compile("metrics.cnt > 50 && now > 0");
        assertThat(c.referencedVariables()).contains("metrics.cnt");
        assertThat(c.referencedVariables()).doesNotContain("now");
    }
}
