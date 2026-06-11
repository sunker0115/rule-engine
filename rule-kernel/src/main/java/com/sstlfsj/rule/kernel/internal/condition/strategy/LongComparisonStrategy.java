package com.sstlfsj.rule.kernel.internal.condition.strategy;

/** LONG 比较:整型快路径 Long.compare(零分配);非整型/超范围/不可解析回退 DecimalComparisonStrategy(绝不截断)。 */
class LongComparisonStrategy implements ComparisonStrategy {

    private static final DecimalComparisonStrategy FALLBACK = new DecimalComparisonStrategy();

    @Override
    public int compare(Object actual, Object operand) {
        Long a = toLong(actual);
        Long b = toLong(operand);
        if (a == null || b == null) return FALLBACK.compare(actual, operand);
        return Long.compare(a, b);
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        Long a = toLong(actual);
        Long b = toLong(operand);
        if (a == null || b == null) return FALLBACK.equals(actual, operand);
        return a.longValue() == b.longValue();
    }

    /** 整型 Number(Long/Integer/Short/Byte)与整型 String → long;其余(Double/Float/BigDecimal/小数 String/null)→ null(触发回退,不截断)。 */
    private static Long toLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer || o instanceof Short || o instanceof Byte) return ((Number) o).longValue();
        if (o instanceof String str) {
            try { return Long.parseLong(str.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
