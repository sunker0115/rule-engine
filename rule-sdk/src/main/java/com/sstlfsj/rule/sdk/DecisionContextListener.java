package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 带评估上下文的回调,用于动作派发(EvalResultListener 不带 context,无法注入 metric)。 */
@FunctionalInterface
public interface DecisionContextListener {
    /**
     * 一次评估完成后回调。
     *
     * @param event   评估事件
     * @param result  评估结果
     * @param context 评估上下文,候选为空/早返回 miss 时为 null
     */
    void onEvaluated(RuleEvent event, EvalResult result, EvalContext context);
}
