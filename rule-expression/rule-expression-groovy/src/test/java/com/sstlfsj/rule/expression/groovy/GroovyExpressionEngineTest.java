package com.sstlfsj.rule.expression.groovy;

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

class GroovyExpressionEngineTest {

    private final GroovyExpressionEngine engine = new GroovyExpressionEngine();

    private static Map<String, Object> bindings(Map<String, Object> metrics, Map<String, Object> payload) {
        Map<String, Object> b = new HashMap<>();
        b.put("metrics", metrics);
        b.put("payload", payload);
        b.put("subject", Map.of());
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        return b;
    }

    private Object eval(String src, Map<String, Object> b) {
        return engine.evaluate(engine.compile(src), b);
    }

    @Test
    void langIsGroovy() {
        assertThat(engine.lang()).isEqualTo(ExpressionLang.GROOVY.tag());
    }

    @Test
    void evaluatesStringDecision() {
        Object out = eval("payload.amount > 10000 ? 'REVIEW' : 'PASS'", bindings(Map.of(), Map.of("amount", 12000L)));
        assertThat(out).isEqualTo("REVIEW");
    }

    @Test
    void evaluatesJsonSourcedNumericTypes() {
        // Groovy 弱类型:Integer/Double/BigDecimal 自动提升
        String s = "payload.amount > 10000 ? 'REVIEW' : 'PASS'";
        assertThat(eval(s, bindings(Map.of(), Map.of("amount", 12000)))).isEqualTo("REVIEW");                          // Integer
        assertThat(eval(s, bindings(Map.of(), Map.of("amount", 5000)))).isEqualTo("PASS");                            // Integer
        assertThat(eval(s, bindings(Map.of(), Map.of("amount", new java.math.BigDecimal("12000"))))).isEqualTo("REVIEW"); // BigDecimal
        assertThat(eval(s, bindings(Map.of(), Map.of("amount", 12000.0)))).isEqualTo("REVIEW");                       // Double
        assertThat(eval("metrics.score > 50.0", bindings(Map.of("score", 72.5f), Map.of()))).isEqualTo(true);        // Float
    }

    @Test
    void evaluatesBooleanAndNumber() {
        assertThat(eval("metrics.cnt > 50", bindings(Map.of("cnt", 53L), Map.of()))).isEqualTo(true);
        assertThat(eval("metrics.cnt > 50", bindings(Map.of("cnt", 40L), Map.of()))).isEqualTo(false);
        assertThat(((Number) eval("metrics.score + 0.5", bindings(Map.of("score", 10.0), Map.of()))).doubleValue())
                .isEqualTo(10.5);
    }

    @Test
    void evaluatesNowAsEpochMillis() {
        assertThat(eval("now > 1577836800000", bindings(Map.of(), Map.of()))).isEqualTo(true); // 2020-01-01T00:00:00Z
    }

    @Test
    void evaluatesStringMethods() {
        // 合法 String 方法放行(白名单接收者)
        assertThat(eval("payload.tag.toUpperCase()", bindings(Map.of(), Map.of("tag", "vip")))).isEqualTo("VIP");
    }

    @Test
    void compileCachesBySource() {
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
        assertThatThrownBy(() -> engine.compile("payload.x >>>> "))
                .isInstanceOf(ExpressionCompileException.class);
    }

    @Test
    void subjectVariableAccessible() {
        Map<String, Object> b = new HashMap<>();
        b.put("metrics", Map.of());
        b.put("payload", Map.of());
        b.put("subject", Map.of("level", "VIP"));
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(eval("subject.level == 'VIP'", b)).isEqualTo(true);
    }

    @Test
    void varPathsFiltersNonNamespacePaths() {
        CompiledExpression c = engine.compile("metrics.cnt > 50 && now > 0");
        assertThat(c.referencedVariables()).contains("metrics.cnt");
        assertThat(c.referencedVariables()).doesNotContain("now");
    }

    // ---- safe-by-design:逃逸用例,全部必须被拦(ExpressionEvaluateException) ----

    @Test
    void safeByDesignBlocksRuntimeExec() {
        assertThatThrownBy(() -> eval("Runtime.getRuntime().exec('echo PWNED')", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
        assertThatThrownBy(() -> eval("java.lang.Runtime.getRuntime().exec('echo PWNED')", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void safeByDesignBlocksStringExecute() {
        // Groovy GDK 的 String.execute()——经命令行执行,必须被拦
        assertThatThrownBy(() -> eval("'echo PWNED'.execute().text", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void safeByDesignBlocksSystemExit() {
        assertThatThrownBy(() -> eval("System.exit(0)", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void safeByDesignBlocksReflection() {
        assertThatThrownBy(() -> eval("Class.forName('java.lang.Runtime')", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
        // 经 getClass()/class 属性的反射跳板
        assertThatThrownBy(() -> eval("''.class.classLoader", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
        assertThatThrownBy(() -> eval("''.getClass()", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void safeByDesignBlocksMetaClassEscape() {
        assertThatThrownBy(() -> eval("'x'.metaClass", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void safeByDesignBlocksNewInstance() {
        assertThatThrownBy(() -> eval("new File('/etc/passwd').exists()", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
        assertThatThrownBy(() -> eval("new java.io.File('/etc/passwd').exists()", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void safeByDesignBlocksEval() {
        assertThatThrownBy(() -> eval("Eval.me('System.exit(0)')", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void safeByDesignBlocksThreadAndClosureEscape() {
        assertThatThrownBy(() -> eval("Thread.start { 1 }", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
        // 闭包里藏命令执行——闭包调用时拦截器对其求值,execute 仍被拦
        assertThatThrownBy(() -> eval("({ -> 'echo PWNED'.execute() })()", bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void evaluateFailureThrowsEvaluateException() {
        // 对 Integer 调用不存在的 toUpperCase()——求值期抛异常
        CompiledExpression c = engine.compile("payload.name.toUpperCase()");
        assertThatThrownBy(() -> engine.evaluate(c, bindings(Map.of(), Map.of("name", 123))))
                .isInstanceOf(ExpressionEvaluateException.class);
    }
}
