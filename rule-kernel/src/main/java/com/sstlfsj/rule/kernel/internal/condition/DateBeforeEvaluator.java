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
 * DATE_BEFORE 条件算子：metric 值严格早于 threshold。
 * 解析段把 actual/threshold 解析为 LocalDate（DATE）或 Instant（DATETIME，含 dataType=null 的 DSL 默认），
 * 再交给对应纯策略比较。支持 $now/$today、带 offset 字符串、裸日期 + params.timezone。
 */
public class DateBeforeEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        return DateComparisonSupport.evaluate(node, ctx, true);
    }

    @Override
    public Optional<OperatorSpec> spec() {
        return Optional.of(OperatorSpec.builder().code(ConditionTypes.DATE_BEFORE).displayName("早于")
                .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
                .allowedDataTypes(Set.of(DataType.DATE.tag(), DataType.DATETIME.tag()))
                .requiresMetric(true).build());
    }
}
