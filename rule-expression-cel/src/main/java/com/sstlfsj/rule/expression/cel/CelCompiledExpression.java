package com.sstlfsj.rule.expression.cel;

import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import dev.cel.common.CelAbstractSyntaxTree;

import java.util.Set;

/** dev.cel 编译产物:持有 checked AST(供运行期 plan)与发布期依赖抽取用的引用变量集。 */
public final class CelCompiledExpression implements CompiledExpression {

    private final CelAbstractSyntaxTree ast;
    private final Set<String> referencedVariables;

    /**
     * @param ast                 dev.cel 编译后的 AST
     * @param referencedVariables 引用的变量点路径(如 "metrics.txn_cnt_1d"),发布期冻依赖用
     */
    public CelCompiledExpression(CelAbstractSyntaxTree ast, Set<String> referencedVariables) {
        this.ast = ast;
        this.referencedVariables = Set.copyOf(referencedVariables);
    }

    /** @return dev.cel checked AST(供 CelExpressionEngine.evaluate 创建 Program) */
    public CelAbstractSyntaxTree ast() {
        return ast;
    }

    @Override
    public Set<String> referencedVariables() {
        return referencedVariables;
    }
}
