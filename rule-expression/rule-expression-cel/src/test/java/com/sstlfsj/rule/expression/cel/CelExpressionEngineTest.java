package com.sstlfsj.rule.expression.cel;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ScriptTypeEnv;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CelExpressionEngineTest {

    private final CelExpressionEngine engine = new CelExpressionEngine();

    private Map<String, Object> bindings(Map<String, Object> metrics, Map<String, Object> payload) {
        Map<String, Object> b = new HashMap<>();
        b.put("metrics", metrics);
        b.put("payload", payload);
        b.put("subject", Map.of());
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        return b;
    }

    @Test
    void langIsCel() {
        assertThat(engine.lang()).isEqualTo(ExpressionLang.CEL.tag());
    }

    @Test
    void evaluatesStringDecision() {
        CompiledExpression c = engine.compile("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        Object out = engine.evaluate(c, bindings(Map.of(), Map.of("amount", 12000L)));
        assertThat(out).isEqualTo("REVIEW");
    }

    @Test
    void evaluatesJsonSourcedNumericTypes() {
        // 真实 JSON payload 的数值常是 Integer(小整数)/ Double / BigDecimal,而非 Long;
        // CEL int 比较需 Long、double 需 Double——绑定面须规整,否则 "no matching overload"。
        CompiledExpression gt = engine.compile("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", 12000)))).isEqualTo("REVIEW");        // Integer
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", 5000)))).isEqualTo("PASS");          // Integer
        assertThat(engine.evaluate(gt, bindings(Map.of(), Map.of("amount", new java.math.BigDecimal("12000"))))).isEqualTo("REVIEW"); // BigDecimal
        assertThat(engine.evaluate(engine.compile("metrics.score > 50.0"),
                bindings(Map.of("score", 72.5f), Map.of()))).isEqualTo(true);   // Float→double
    }

    @Test
    void evaluatesBooleanAndNumber() {
        assertThat(engine.evaluate(engine.compile("metrics.cnt > 50"),
                bindings(Map.of("cnt", 53L), Map.of()))).isEqualTo(true);
        assertThat(engine.evaluate(engine.compile("metrics.score + 0.5"),
                bindings(Map.of("score", 10.0), Map.of()))).isEqualTo(10.5);
    }

    @Test
    void evaluatesNowAsTimestamp() {
        // 验证 now(Instant)经 adaptBindings 转 protobuf Timestamp 后可参与时间比较
        CompiledExpression c = engine.compile("now > timestamp('2020-01-01T00:00:00Z')");
        assertThat(engine.evaluate(c, bindings(Map.of(), Map.of()))).isEqualTo(true);
    }

    @Test
    void compileCachesBySource() {
        CompiledExpression a = engine.compile("payload.x > 1");
        CompiledExpression b = engine.compile("payload.x > 1");
        assertThat(a).isSameAs(b);   // 同源命中缓存,同一实例
    }

    @Test
    void exposesReferencedVariables() {
        CompiledExpression c = engine.compile("metrics.txn_cnt_1d > 50 && payload.amount > 0");
        assertThat(c.referencedVariables()).contains("metrics.txn_cnt_1d", "payload.amount");
    }

    @Test
    void syntaxErrorThrowsCompileException() {
        assertThatThrownBy(() -> engine.compile("metrics.x >>> "))
                .isInstanceOf(ExpressionCompileException.class);
    }

    @Test
    void ioAndReflectionNotExpressible() {
        // safe-by-design:CEL 无文件/反射/类加载内建,此类标识符编译期即不可解析 → 拒
        assertThatThrownBy(() -> engine.compile("java.lang.Runtime.getRuntime()"))
                .isInstanceOf(ExpressionCompileException.class);
    }

    @Test
    void typeCheckPassesForWellTypedExpression() {
        // LONG metric + STRING payload,各自类型匹配的用法
        ScriptTypeEnv env = new ScriptTypeEnv(
                Map.of("cnt", DataType.LONG),
                Map.of("country", DataType.STRING));
        engine.typeCheck("metrics.cnt > 50 && payload.country == 'US'", env);  // 不抛
    }

    @Test
    void typeCheckRejectsStringComparedAsNumber() {
        // STRING 字段参与数值比较 → 类型不符,发布期即拒
        ScriptTypeEnv env = new ScriptTypeEnv(Map.of(), Map.of("name", DataType.STRING));
        assertThatThrownBy(() -> engine.typeCheck("payload.name > 10000", env))
                .isInstanceOf(ExpressionCompileException.class);
    }

    @Test
    void typeCheckLenientOnDecimal() {
        // DECIMAL(number)→DYN:整数字面量比较不误判(对齐运行期数值规整)
        ScriptTypeEnv env = new ScriptTypeEnv(Map.of(), Map.of("amount", DataType.DECIMAL));
        engine.typeCheck("payload.amount > 10000 ? 'REVIEW' : 'PASS'", env);  // 不抛
    }

    @Test
    void typeCheckSubjectIsOpen() {
        // subject.* 开放(dyn),任意访问不报类型错
        engine.typeCheck("subject.level == 'VIP'", new ScriptTypeEnv(Map.of(), Map.of()));
    }

    @Test
    void evaluatesParamsVariable() {
        // params 命名空间(参数化模板注入槽位值)须声明为 map(string,dyn),否则 CEL 编译期拒未声明变量
        CompiledExpression c = engine.compile("params.threshold > 50");
        Map<String, Object> b = new HashMap<>();
        b.put("params", Map.of("threshold", 75));
        b.put("metrics", Map.of());
        b.put("payload", Map.of());
        b.put("subject", Map.of());
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(engine.evaluate(c, b)).isEqualTo(true);
    }

    @Test
    void typeCheckParamsIsOpen() {
        // params.* 开放(dyn),类型检查不报未声明变量错
        engine.typeCheck("params.threshold > 50", new ScriptTypeEnv(Map.of(), Map.of()));
    }

    @Test
    void evaluatesBigDecimalFractionInParams() {
        // BigDecimal 小数经 normalizeNumerics→stripTrailingZeros().scale()>0→doubleValue() 分支
        CompiledExpression c = engine.compile("params.threshold == 75.5");
        Map<String, Object> b = new HashMap<>();
        b.put("params", Map.of("threshold", new java.math.BigDecimal("75.5")));
        b.put("metrics", Map.of());
        b.put("payload", Map.of());
        b.put("subject", Map.of());
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(engine.evaluate(c, b)).isEqualTo(true);
    }
}
