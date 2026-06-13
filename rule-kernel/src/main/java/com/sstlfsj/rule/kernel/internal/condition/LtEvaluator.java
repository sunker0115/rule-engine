package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;

import java.util.Optional;
import java.util.Set;

/** LT（小于）条件算子：actual &lt; threshold。 */
@ConditionType(value = ConditionTypes.LT, displayName = "小于", schema = ParamSpec.NUMERIC)
public class LtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp < 0; }

    @Override
    public Optional<OperatorSpec> spec() {
        return Optional.of(OperatorSpec.builder().code(ConditionTypes.LT).displayName("小于")
                .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
                .allowedDataTypes(Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag()))
                .requiresMetric(true).build());
    }
}
