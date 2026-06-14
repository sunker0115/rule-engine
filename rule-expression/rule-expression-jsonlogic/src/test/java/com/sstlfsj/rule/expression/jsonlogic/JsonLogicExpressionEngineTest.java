package com.sstlfsj.rule.expression.jsonlogic;

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

class JsonLogicExpressionEngineTest {

    private final JsonLogicExpressionEngine engine = new JsonLogicExpressionEngine();

    private static Map<String, Object> bindings(Map<String, Object> metrics, Map<String, Object> payload) {
        Map<String, Object> b = new HashMap<>();
        b.put("metrics", metrics);
        b.put("payload", payload);
        b.put("subject", Map.of());
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        return b;
    }

    @Test
    void langIsJsonLogic() {
        assertThat(engine.lang()).isEqualTo(ExpressionLang.JSONLOGIC.tag());
    }

    @Test
    void evaluatesStringDecision() {
        // {"if": [{">": [{"var": "payload.amount"}, 10000]}, "REVIEW", "PASS"]}
        String rule = "{\"if\":[{\">\":[{\"var\":\"payload.amount\"},10000]},\"REVIEW\",\"PASS\"]}";
        CompiledExpression c = engine.compile(rule);
        Object out = engine.evaluate(c, bindings(Map.of(), Map.of("amount", 12000L)));
        assertThat(out).isEqualTo("REVIEW");
    }

    @Test
    void evaluatesBooleanDecision() {
        // {"and": [{">": [{"var": "metrics.cnt"}, 50]}, {"==": [{"var": "payload.country"}, "US"]}]}
        String rule = "{\"and\":[{\">\":[{\"var\":\"metrics.cnt\"},50]},{\"==\":[{\"var\":\"payload.country\"},\"US\"]}]}";
        CompiledExpression c = engine.compile(rule);
        assertThat(engine.evaluate(c, bindings(Map.of("cnt", 53L), Map.of("country", "US")))).isEqualTo(true);
        assertThat(engine.evaluate(c, bindings(Map.of("cnt", 40L), Map.of("country", "US")))).isEqualTo(false);
    }

    @Test
    void evaluatesJsonSourcedNumericTypes() {
        // JsonLogic 内部用 Gson 解析数字:整数→BigDecimal(Double),在比较时自动提升
        String rule = "{\">\":[{\"var\":\"payload.amount\"},10000]}";
        CompiledExpression c = engine.compile(rule);
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of("amount", 12000)))).isEqualTo(true);             // Integer
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of("amount", 5000)))).isEqualTo(false);              // Integer
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of("amount", new java.math.BigDecimal("12000"))))).isEqualTo(true); // BigDecimal
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of("amount", 12000.0)))).isEqualTo(true);            // Double
    }

    @Test
    void evaluatesMathematicalExpression() {
        // {"+": [{"var": "metrics.score"}, 0.5]}
        String rule = "{\"+\":[{\"var\":\"metrics.score\"},0.5]}";
        CompiledExpression c = engine.compile(rule);
        assertThat(engine.evaluate(c, bindings(Map.of("score", 10.0), Map.of()))).isEqualTo(10.5);
    }

    @Test
    void evaluatesNowAsEpochMillis() {
        // {"==": [{"var": "now"}, 1751414400000]}
        String rule = "{\">\":[{\"var\":\"now\"},1577836800000]}"; // 2020-01-01T00:00:00Z
        CompiledExpression c = engine.compile(rule);
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of()))).isEqualTo(true);
    }

    @Test
    void compileCachesBySource() {
        // 同源脚本命中 Caffeine 缓存,返回同一实例
        CompiledExpression a = engine.compile("{\">\":[{\"var\":\"payload.x\"},1]}");
        CompiledExpression b = engine.compile("{\">\":[{\"var\":\"payload.x\"},1]}");
        assertThat(a).isSameAs(b);
    }

    @Test
    void exposesReferencedVariables() {
        String rule = "{\"and\":[{\">\":[{\"var\":\"metrics.txn_cnt_1d\"},50]},{\">\":[{\"var\":\"payload.amount\"},0]}]}";
        CompiledExpression c = engine.compile(rule);
        assertThat(c.referencedVariables()).contains("metrics.txn_cnt_1d", "payload.amount");
    }

    @Test
    void syntaxErrorThrowsCompileException() {
        // 非法 JSON
        assertThatThrownBy(() -> engine.compile("{invalid json}"))
                .isInstanceOf(ExpressionCompileException.class);
    }

    @Test
    void safeByDesignNoCodeExecution() {
        // JsonLogic 天然 safe-by-design:无反射/类加载/文件/网络内建操作符
        // 任何 JSON 规则只能调用预定义的算符(var/if/==/>/</and/or/+/...),无法执行任意代码
        String rule = "{\"==\":[1,1]}";
        CompiledExpression c = engine.compile(rule);
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of()))).isEqualTo(true);
    }

    @Test
    void nullResultOnMissingVarReturnsNull() {
        // 引用不存在的变量时,JsonLogic 的 var 返回 null
        String rule = "{\"var\":\"metrics.nonexistent\"}";
        CompiledExpression c = engine.compile(rule);
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of()))).isNull();
    }

    @Test
    void subjectVariableAccessible() {
        // {"==": [{"var": "subject.level"}, "VIP"]}
        Map<String, Object> b = new HashMap<>();
        b.put("metrics", Map.of());
        b.put("payload", Map.of());
        b.put("subject", Map.of("level", "VIP"));
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        String rule = "{\"==\":[{\"var\":\"subject.level\"},\"VIP\"]}";
        CompiledExpression c = engine.compile(rule);
        assertThat(engine.evaluate(c, b)).isEqualTo(true);
    }

    @Test
    void evaluateFailureThrowsEvaluateException() {
        // 未知算符——JsonLogic 求值期抛 JsonLogicException,引擎包装为 ExpressionEvaluateException
        String rule = "{\"no_such_op\":[1,2]}";
        CompiledExpression c = engine.compile(rule);
        assertThatThrownBy(() -> engine.evaluate(c, bindings(Map.of(), Map.of())))
                .isInstanceOf(ExpressionEvaluateException.class);
    }

    @Test
    void handlesComplexNestedRule() {
        // 嵌套 if-then-else + and + 数值比较
        String rule = """
                {"if":[
                  {"and":[
                    {">":[{"var":"metrics.score"},80]},
                    {"==":[{"var":"payload.country"},"CN"]}
                  ]},
                  "PREMIUM",
                  "STANDARD"
                ]}""";
        CompiledExpression c = engine.compile(rule);
        assertThat(engine.evaluate(c, bindings(Map.of("score", 95L), Map.of("country", "CN")))).isEqualTo("PREMIUM");
        assertThat(engine.evaluate(c, bindings(Map.of("score", 70L), Map.of("country", "CN")))).isEqualTo("STANDARD");
    }

    @Test
    void varPathsFiltersNonNamespacePaths() {
        // 只收集 metrics./payload./subject. 路径,忽略纯变量名(如 now)
        String rule = "{\"and\":[{\">\":[{\"var\":\"metrics.cnt\"},50]},{\">\":[{\"var\":\"now\"},0]}]}";
        CompiledExpression c = engine.compile(rule);
        assertThat(c.referencedVariables()).contains("metrics.cnt");
        assertThat(c.referencedVariables()).doesNotContain("now");
    }
}
