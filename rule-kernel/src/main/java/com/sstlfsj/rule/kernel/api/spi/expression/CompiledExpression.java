package com.sstlfsj.rule.kernel.api.spi.expression;

import java.util.Set;

/** 编译后的表达式产物(引擎实现持有引擎私有句柄);referencedVariables 供发布期依赖抽取与校验。 */
public interface CompiledExpression {
    /**
     * 表达式引用的变量点路径集合(如 "metrics.txn_cnt_1d" / "payload.amount")。
     * @return 引用变量集合;无法静态枚举时返回空集
     */
    Set<String> referencedVariables();
}
