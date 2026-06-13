package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
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
        Object actual = null;
        String source = null;
        if (mc != null) {
            MetricValue mv = ctx.getMetric(mc);
            // 先捕获实际值/来源，再判断取数是否失败，保证 ERROR 也带出来源
            if (mv != null) { actual = mv.value(); source = mv.valueSource(); }
            if (mv != null && mv.isError()) {
                // provider 开放码原样穿透；缺码时落规范码 .name()，整条 ternary 保持 String 走 String 重载
                return ConditionOutcome.error(
                        mv.errorCode() != null ? mv.errorCode() : EvalErrorCode.METRIC_FETCH_FAIL.name(), actual, source);
            }
        }
        ConditionEvaluator evaluator = evaluators.get(node.conditionType());
        if (evaluator == null) return ConditionOutcome.error(EvalErrorCode.NO_EVALUATOR, actual, source);
        return ConditionOutcome.leaf(evaluator.evaluate(node, ctx), actual, source);
    }

    /**
     * 布尔快路径：{@link #evaluate} 布尔投影的单一真相源(metric 取数失败 / evaluator 为 null → false)，
     * 不构建 {@link ConditionOutcome}、不携带 trace 值。供解释器非 trace 叶子与编译执行器叶子共用——
     * 前者每次查 map 解析 evaluator，后者编译期绑定 evaluator(巨态分派下更稳)。
     *
     * @param node      条件节点
     * @param ctx       执行上下文
     * @param evaluator 已解析的算子(调用方在编译期/调用期解析)；null 视为无算子 → false
     * @return 条件是否满足；metric ERROR / 无算子均为 false，与 {@link #evaluate} 布尔投影逐一致
     */
    static boolean satisfiesBoolean(ConditionNode node, EvalContext ctx, ConditionEvaluator evaluator) {
        String mc = node.metricCode();
        if (mc != null) {
            MetricValue mv = ctx.getMetric(mc);
            if (mv != null && mv.isError()) return false;
        }
        if (evaluator == null) return false;
        return evaluator.evaluate(node, ctx);
    }
}
