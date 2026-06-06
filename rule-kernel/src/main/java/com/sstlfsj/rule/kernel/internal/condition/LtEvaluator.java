package com.sstlfsj.rule.kernel.internal.condition;

/** LT（小于）条件算子：actual &lt; threshold。 */
public class LtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp < 0; }
}
