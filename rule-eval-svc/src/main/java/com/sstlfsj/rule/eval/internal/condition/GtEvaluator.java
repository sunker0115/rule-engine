package com.sstlfsj.rule.eval.internal.condition;

import org.springframework.stereotype.Component;

/** GT（大于）条件算子：actual > threshold。 */
@Component("GT")
class GtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean compare(int cmp) { return cmp > 0; }
}
