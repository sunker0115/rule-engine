package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;

/** LTE（小于等于）条件算子：actual &lt;= threshold。 */
@ConditionType(value = ConditionTypes.LTE, displayName = "小于等于", schema = ParamSpec.NUMERIC)
public class LteEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp <= 0; }
}
