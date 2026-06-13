package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;

/** LT（小于）条件算子：actual &lt; threshold。 */
@ConditionType(value = ConditionTypes.LT, displayName = "小于", schema = ParamSpec.NUMERIC)
public class LtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp < 0; }
}
