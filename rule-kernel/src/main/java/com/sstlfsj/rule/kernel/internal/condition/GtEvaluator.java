package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;

import java.util.Optional;
import java.util.Set;

/** GT（大于）条件算子：actual > threshold。 */
@ConditionType(value = ConditionTypes.GT, displayName = "大于", schema = ParamSpec.NUMERIC)
public class GtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp > 0; }

    @Override
    public Optional<OperatorSpec> spec() {
        return Optional.of(OperatorSpec.builder().code(ConditionTypes.GT).displayName("大于")
                .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
                .allowedDataTypes(Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag()))
                .requiresMetric(true).build());
    }
}
