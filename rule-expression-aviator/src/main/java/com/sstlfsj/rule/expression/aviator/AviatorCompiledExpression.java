package com.sstlfsj.rule.expression.aviator;

import com.googlecode.aviator.Expression;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;

import java.util.Set;

/** Aviator 编译产物:持有编译后的 Expression,供运行期执行与发布期依赖抽取。 */
public final class AviatorCompiledExpression implements CompiledExpression {

    private final Expression expression;
    private final Set<String> referencedVariables;

    /**
     * @param expression          Aviator 编译后的 Expression
     * @param referencedVariables 引用的变量点路径(如 "metrics.txn_cnt_1d"),发布期冻依赖用
     */
    public AviatorCompiledExpression(Expression expression, Set<String> referencedVariables) {
        this.expression = expression;
        this.referencedVariables = Set.copyOf(referencedVariables);
    }

    /** @return Aviator Expression(供 AviatorExpressionEngine.evaluate 执行) */
    public Expression expression() {
        return expression;
    }

    @Override
    public Set<String> referencedVariables() {
        return referencedVariables;
    }
}
