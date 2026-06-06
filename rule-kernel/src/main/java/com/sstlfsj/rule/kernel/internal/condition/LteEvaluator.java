package com.sstlfsj.rule.kernel.internal.condition;

/** LTE（小于等于）条件算子：actual &lt;= threshold。 */
public class LteEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp <= 0; }
}
