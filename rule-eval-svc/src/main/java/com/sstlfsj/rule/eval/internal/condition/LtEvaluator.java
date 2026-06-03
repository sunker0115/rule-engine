package com.sstlfsj.rule.eval.internal.condition;

import org.springframework.stereotype.Component;

/** LT（小于）条件算子：actual < threshold。 */
@Component("LT")
class LtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean compare(int cmp) { return cmp < 0; }
}
