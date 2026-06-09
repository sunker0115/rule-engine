package com.sstlfsj.rule.kernel.api.model;

/**
 * 指标/比较数据类型（契约值，frozen 进 ConditionNode.dataType、= DB metric_definition.data_type）。
 * 常量名即落库/序列化用的字符串 tag，与持久化值逐字节相等。
 * UNKNOWN 为运行时哨兵（取数失败降级 / 无定义 metric），非合法 metric 类型，不进 MetricEnums.DATA_TYPES。
 */
public enum DataType {
    LONG, DOUBLE, DECIMAL, STRING, BOOLEAN, DATE, DATETIME, LIST, UNKNOWN;

    /** @return 落库/序列化用的字符串标签（= 常量名）。 */
    public String tag() {
        return name();
    }

    /**
     * 字符串 tag → 枚举。
     *
     * @param tag dataType 字符串值（如 metric_definition.data_type、ConditionNode.dataType）
     * @return 对应枚举；null 或未识别 → UNKNOWN（供分派兜底，语义同原 default）
     */
    public static DataType fromTag(String tag) {
        if (tag == null) return UNKNOWN;
        for (DataType d : values()) {
            if (d.name().equals(tag)) return d;
        }
        return UNKNOWN;
    }
}
