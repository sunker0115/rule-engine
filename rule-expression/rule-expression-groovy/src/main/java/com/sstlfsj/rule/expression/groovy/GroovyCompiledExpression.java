package com.sstlfsj.rule.expression.groovy;

import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;

import java.util.Set;

/** Groovy 编译产物:持有编译后的脚本 Class,供运行期实例化执行与发布期依赖抽取。 */
public final class GroovyCompiledExpression implements CompiledExpression {

    private final Class<?> scriptClass;
    private final Set<String> referencedVariables;

    /**
     * @param scriptClass         Groovy 编译后的 Script 子类(经 SandboxTransformer 改写)
     * @param referencedVariables 引用的变量点路径(如 "metrics.txn_cnt_1d"),发布期冻依赖用
     */
    public GroovyCompiledExpression(Class<?> scriptClass, Set<String> referencedVariables) {
        this.scriptClass = scriptClass;
        this.referencedVariables = Set.copyOf(referencedVariables);
    }

    /** @return Groovy 脚本 Class(供 GroovyExpressionEngine.evaluate 实例化执行) */
    public Class<?> scriptClass() {
        return scriptClass;
    }

    @Override
    public Set<String> referencedVariables() {
        return referencedVariables;
    }
}
