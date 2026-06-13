package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

/**
 * DATE_AFTER 条件算子：metric 值严格晚于 threshold。解析与比较逻辑同 DateBeforeEvaluator。
 */
@ConditionType(value = ConditionTypes.DATE_AFTER, displayName = "晚于", schema = ParamSpec.DATE_COMPARE)
public class DateAfterEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        return DateComparisonSupport.evaluate(node, ctx, false);
    }
}
