package com.sstlfsj.rule.expression.jsonlogic;

import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;

import java.util.Set;

/** JsonLogic 编译产物:持有原始 JSON 规则字符串,供运行期执行与发布期依赖抽取。 */
public final class JsonLogicCompiledExpression implements CompiledExpression {

    private final String source;
    private final Set<String> referencedVariables;

    /**
     * @param source                JSON 规则字符串(json-logic-java 的 apply 接收 String)
     * @param referencedVariables   引用的变量点路径(如 "metrics.txn_cnt_1d"),从 {"var":"..."} 抽取
     */
    public JsonLogicCompiledExpression(String source, Set<String> referencedVariables) {
        this.source = source;
        this.referencedVariables = Set.copyOf(referencedVariables);
    }

    /** @return JSON 规则字符串(供 JsonLogicExpressionEngine.evaluate 传给 jsonLogic.apply) */
    public String source() {
        return source;
    }

    @Override
    public Set<String> referencedVariables() {
        return referencedVariables;
    }
}
