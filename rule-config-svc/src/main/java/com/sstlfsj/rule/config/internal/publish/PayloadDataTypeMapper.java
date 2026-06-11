package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.DataType;

/** 把 payloadSchema 的 JSON Schema type 映射到 kernel DataType 标签,供发布期注入 ConditionNode.dataType。 */
final class PayloadDataTypeMapper {
    private PayloadDataTypeMapper() {}

    /**
     * @param schemaType payloadSchema 字段类型（STRING/INTEGER/NUMBER/BOOLEAN/ARRAY/OBJECT）
     * @return 对应的 DataType.tag();无法识别(含 null/OBJECT)返回 UNKNOWN
     */
    static String toDataTypeTag(String schemaType) {
        if (schemaType == null) return DataType.UNKNOWN.tag();
        return switch (schemaType.toUpperCase()) {
            case "NUMBER"  -> DataType.DECIMAL.tag();
            case "INTEGER" -> DataType.LONG.tag();
            case "STRING"  -> DataType.STRING.tag();
            case "BOOLEAN" -> DataType.BOOLEAN.tag();
            case "ARRAY"   -> DataType.LIST.tag();
            default        -> DataType.UNKNOWN.tag();
        };
    }
}
