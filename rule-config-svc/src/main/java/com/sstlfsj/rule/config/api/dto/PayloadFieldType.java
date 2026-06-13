package com.sstlfsj.rule.config.api.dto;

/** payloadSchema 字段类型封闭集(JSON-shape authoring 词汇);经 PayloadDataTypeMapper 桥接到 kernel DataType。 */
public enum PayloadFieldType {
    STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT;

    /**
     * 解析 type 串为枚举,非法抛 IllegalArgumentException(含合法集)。
     *
     * @param tag payloadSchema 字段声明的 type
     * @return 对应枚举
     */
    public static PayloadFieldType fromTag(String tag) {
        if (tag == null) {
            throw new IllegalArgumentException("payloadSchema 字段 type 不能为空，合法值: STRING/INTEGER/NUMBER/BOOLEAN/ARRAY/OBJECT");
        }
        try {
            return valueOf(tag.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 payloadSchema 字段 type=" + tag
                    + "，合法值: STRING/INTEGER/NUMBER/BOOLEAN/ARRAY/OBJECT");
        }
    }
}
