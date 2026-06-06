package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.Map;

/**
 * 统一条件求值门面：在委托 ConditionEvaluator 前拦截「引用的 metric 取数失败」，返回三态。
 * 集中降级语义，避免散落各执行器、避免依赖 evaluator 对 null 的巧合行为；
 * 未注册 evaluator 也归入 ERROR(NO_EVALUATOR)，由各执行器按语义决定动作。
 */
final class ConditionEvaluation {

    /** 取数失败错误码。 */
    static final String METRIC_FETCH_FAIL = "METRIC_FETCH_FAIL";
    /** 无注册算子错误码。 */
    static final String NO_EVALUATOR = "NO_EVALUATOR";

    private ConditionEvaluation() {}

    /**
     * 求值单个条件节点。
     *
     * @param node       条件节点
     * @param ctx        执行上下文
     * @param evaluators conditionType → evaluator 映射
     * @return 三态结果
     */
    static ConditionOutcome evaluate(ConditionNode node, EvalContext ctx,
                                     Map<String, ConditionEvaluator> evaluators) {
        String mc = node.metricCode();
        if (mc != null) {
            MetricValue mv = ctx.getMetric(mc);
            if (mv != null && mv.isError()) {
                return ConditionOutcome.error(
                        mv.errorCode() != null ? mv.errorCode() : METRIC_FETCH_FAIL);
            }
        }
        ConditionEvaluator evaluator = evaluators.get(node.conditionType());
        if (evaluator == null) return ConditionOutcome.error(NO_EVALUATOR);
        return ConditionOutcome.of(evaluator.evaluate(node, ctx));
    }
}
