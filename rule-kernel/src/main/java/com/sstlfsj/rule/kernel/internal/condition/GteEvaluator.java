package com.sstlfsj.rule.kernel.internal.condition;

/** GTE（大于等于）条件算子：actual >= threshold。 */
public class GteEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean compare(int cmp) { return cmp >= 0; }
}
