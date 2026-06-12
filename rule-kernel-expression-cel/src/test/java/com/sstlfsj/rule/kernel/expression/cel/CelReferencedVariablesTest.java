package com.sstlfsj.rule.kernel.expression.cel;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CelReferencedVariablesTest {

    private CelAbstractSyntaxTree compile(String expr) throws Exception {
        CelCompiler c = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("metrics", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("payload", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("subject", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("now", SimpleType.TIMESTAMP)
                .build();
        return c.compile(expr).getAst();
    }

    @Test
    void extractsNamespacedSelects() throws Exception {
        Set<String> vars = CelReferencedVariables.from(
                compile("metrics.txn_cnt_1d > 50 && payload.amount > 10000 ? 'R' : 'P'"));
        assertThat(vars).contains("metrics.txn_cnt_1d", "payload.amount");
    }

    @Test
    void ignoresNonNamespacedAndNow() throws Exception {
        Set<String> vars = CelReferencedVariables.from(compile("now > timestamp('2020-01-01T00:00:00Z')"));
        assertThat(vars).isEmpty();   // now 不是 metrics/payload/subject 命名空间,不计入依赖
    }
}
