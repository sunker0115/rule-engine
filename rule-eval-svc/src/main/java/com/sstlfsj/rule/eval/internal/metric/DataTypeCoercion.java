package com.sstlfsj.rule.eval.internal.metric;

import java.math.BigDecimal;

/** 把取数原始值（ResultSet / JSON）按 metric dataType 强转。null 透传。 */
public final class DataTypeCoercion {

    private DataTypeCoercion() {}

    /**
     * 按 dataType 强转。
     *
     * @param raw      原始值（可能为 Number / String / Boolean / null）
     * @param dataType LONG/DOUBLE/STRING/BOOLEAN/DATE/DATETIME
     * @return 强转后的值；无法转换时返回 null
     */
    public static Object coerce(Object raw, String dataType) {
        if (raw == null || dataType == null) return raw;
        try {
            return switch (dataType) {
                case "LONG" -> toLong(raw);
                case "DOUBLE" -> toDouble(raw);
                case "BOOLEAN" -> toBoolean(raw);
                // STRING / DATE / DATETIME：字符串化，交由 evaluator 的 PlaceholderResolver 再解析
                default -> raw.toString();
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static Long toLong(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof BigDecimal b) return b.longValue();
        return Long.parseLong(raw.toString().trim());
    }

    private static Double toDouble(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        return Double.parseDouble(raw.toString().trim());
    }

    private static Boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.doubleValue() != 0;
        String s = raw.toString().trim();
        return "1".equals(s) || Boolean.parseBoolean(s);
    }
}
