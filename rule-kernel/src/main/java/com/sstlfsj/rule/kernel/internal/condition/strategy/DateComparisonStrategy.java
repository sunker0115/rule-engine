package com.sstlfsj.rule.kernel.internal.condition.strategy;

import java.time.LocalDate;

/**
 * DATE 比较策略（B20 §5.3）：只接收已类型化的 LocalDate，纯比较。
 * $today / 裸日期 / 时区补全在 evaluator 解析段完成，本策略不接触 EvalContext。
 */
class DateComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        if (actual instanceof LocalDate a && operand instanceof LocalDate b) {
            return a.compareTo(b);
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        return actual instanceof LocalDate a && operand instanceof LocalDate b && a.equals(b);
    }
}
