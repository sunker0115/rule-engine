package com.sstlfsj.rule.expression.jexl;

import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import org.apache.commons.jexl3.JexlScript;

import java.util.Set;

/** JEXL 编译产物:持有编译后的 JexlScript,供运行期执行与发布期依赖抽取。 */
public final class JexlCompiledExpression implements CompiledExpression {

    private final JexlScript script;
    private final Set<String> referencedVariables;

    /**
     * @param script              JEXL 编译后的脚本
     * @param referencedVariables 引用的变量点路径(如 "metrics.txn_cnt_1d"),发布期冻依赖用
     */
    public JexlCompiledExpression(JexlScript script, Set<String> referencedVariables) {
        this.script = script;
        this.referencedVariables = Set.copyOf(referencedVariables);
    }

    /** @return JEXL 脚本(供 JexlExpressionEngine.evaluate 执行) */
    public JexlScript script() {
        return script;
    }

    @Override
    public Set<String> referencedVariables() {
        return referencedVariables;
    }
}
