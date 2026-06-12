package com.sstlfsj.rule.kernel.expression.cel;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CelCompiledExpressionTest {

    private CelAbstractSyntaxTree compile(String expr) throws Exception {
        CelCompiler c = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("metrics", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .build();
        return c.compile(expr).getAst();
    }

    @Test
    void holdsAstAndReferencedVariables() throws Exception {
        CelAbstractSyntaxTree ast = compile("metrics.cnt > 1");
        CelCompiledExpression compiled =
                new CelCompiledExpression(ast, Set.of("metrics.cnt"));

        assertThat(compiled.ast()).isSameAs(ast);
        assertThat(compiled.referencedVariables()).containsExactly("metrics.cnt");
    }

    @Test
    void referencedVariablesIsImmutableCopy() throws Exception {
        CelAbstractSyntaxTree ast = compile("metrics.cnt > 1");
        CelCompiledExpression compiled =
                new CelCompiledExpression(ast, Set.of("metrics.cnt"));

        // Set.copyOf 防御性拷贝:返回不可变视图,外部不可改
        assertThat(compiled.referencedVariables()).isUnmodifiable();
    }
}
