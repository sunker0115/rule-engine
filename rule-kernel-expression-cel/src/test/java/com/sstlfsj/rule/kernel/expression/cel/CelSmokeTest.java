package com.sstlfsj.rule.kernel.expression.cel;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dev.cel 冒烟验证：证明 CEL-Java 在 classpath、dyn env（map(string,dyn)）下能编译表达式
 * 并对 Java {@link Map} 求值。这是 CEL 引擎落地前的地基验证关。
 */
class CelSmokeTest {

    @Test
    void dynEnvCompilesAndEvaluatesAgainstJavaMap() throws Exception {
        CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("metrics", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("payload", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .build();
        CelAbstractSyntaxTree ast = compiler.compile(
                "metrics.txn_cnt_1d > 50 && payload.amount > 10000 ? 'REVIEW' : 'PASS'").getAst();

        CelRuntime runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
        Object out = runtime.createProgram(ast).eval(Map.of(
                "metrics", Map.of("txn_cnt_1d", 53L),
                "payload", Map.of("amount", 12000L)));

        assertThat(out).isEqualTo("REVIEW");
    }
}
