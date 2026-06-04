package com.sstlfsj.rule.eval.internal.condition;

import org.springframework.stereotype.Component;

/** GTE（大于等于）条件算子：actual >= threshold。 */
@Component("GTE")
class GteEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean compare(int cmp) { return cmp >= 0; }
}
