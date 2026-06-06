package com.sstlfsj.rule.kernel.internal.condition;

/** GT（大于）条件算子：actual > threshold。 */
public class GtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp > 0; }
}
