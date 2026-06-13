package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;

/** GTE（大于等于）条件算子：actual >= threshold。 */
@ConditionType(value = ConditionTypes.GTE, displayName = "大于等于", schema = ParamSpec.NUMERIC)
public class GteEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp >= 0; }
}
