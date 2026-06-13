package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.Optional;
import java.util.Set;

/**
 * DATE_AFTER 条件算子：metric 值严格晚于 threshold。解析与比较逻辑同 DateBeforeEvaluator。
 */
public class DateAfterEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        return DateComparisonSupport.evaluate(node, ctx, false);
    }

    @Override
    public Optional<OperatorSpec> spec() {
        return Optional.of(OperatorSpec.builder().code(ConditionTypes.DATE_AFTER).displayName("晚于")
                .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
                .allowedDataTypes(Set.of(DataType.DATE.tag(), DataType.DATETIME.tag()))
                .requiresMetric(true).build());
    }
}
