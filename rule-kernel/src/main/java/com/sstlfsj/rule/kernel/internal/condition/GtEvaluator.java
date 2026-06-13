package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;

/** GT（大于）条件算子：actual > threshold。 */
@ConditionType(value = ConditionTypes.GT, displayName = "大于", schema = ParamSpec.NUMERIC)
public class GtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp > 0; }
}
