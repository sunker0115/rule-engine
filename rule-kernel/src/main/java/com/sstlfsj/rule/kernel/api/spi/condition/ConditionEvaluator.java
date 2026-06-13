package com.sstlfsj.rule.kernel.api.spi.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;

import java.util.Optional;

/** 对单个 ConditionNode 在当前执行上下文中进行条件求值。 */
public interface ConditionEvaluator {
    /**
     * 在当前执行上下文中对单个条件节点求值。
     *
     * @param node 待求值的条件节点
     * @param ctx  当前评估上下文
     * @return 条件是否满足
     */
    boolean evaluate(ConditionNode node, EvalContext ctx);

    /**
     * 可选:算子声明自身规格,供发布期 param 键校验与元数据暴露使用。
     * 默认返回 {@link Optional#empty()}(= 发布期放行 + 元数据不可见),向后兼容。
     * 内置算子 override 此方法实现单一真相源;自定义 SPI 算子可 opt-in 声明。
     *
     * @return 算子规格;empty = 不声明(放行)
     */
    default Optional<OperatorSpec> spec() {
        return Optional.empty();
    }
}
