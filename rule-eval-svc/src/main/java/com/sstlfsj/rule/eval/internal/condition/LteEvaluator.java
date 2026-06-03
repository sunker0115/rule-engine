package com.sstlfsj.rule.eval.internal.condition;

import org.springframework.stereotype.Component;

/** LTE（小于等于）条件算子：actual <= threshold。 */
@Component("LTE")
class LteEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean compare(int cmp) { return cmp <= 0; }
}
