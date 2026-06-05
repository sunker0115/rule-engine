package com.sstlfsj.rule.kernel.internal.condition.strategy;

import java.time.Instant;

/**
 * DATETIME 比较策略（B20 §5.3）：只接收已类型化的 Instant，纯比较。
 * $now / 带 offset / 裸日期时间补全在 evaluator 解析段完成，本策略不接触 EvalContext。
 */
class DateTimeComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        if (actual instanceof Instant a && operand instanceof Instant b) {
            return a.compareTo(b);
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        return actual instanceof Instant a && operand instanceof Instant b && a.equals(b);
    }
}
