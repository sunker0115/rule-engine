package com.sstlfsj.rule.config.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.List;

/**
 * payloadSchema 单字段声明（JSON Schema 完整子集）。
 * 以 JSON 数组形式存入 scene.payload_schema 列，每个元素对应此 record。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayloadFieldSpec(
        /** 字段名，对应 RuleEvent.payload 的 key。 */
        String name,
        /** 字段类型：STRING / INTEGER / NUMBER / BOOLEAN / ARRAY / OBJECT。 */
        String type,
        /** 是否必填，默认 false。 */
        boolean required,
        /** 枚举值约束；非 null 时 payload 该字段值必须在列表内。 */
        @JsonProperty("enum") List<Object> enumValues,
        /** 数值下界（NUMBER / INTEGER 有效）；null 表示不约束。 */
        Double minimum,
        /** 数值上界（NUMBER / INTEGER 有效）；null 表示不约束。 */
        Double maximum,
        /** 正则约束（STRING 有效）；null 表示不约束。 */
        String pattern,
        /** 字段描述，供运营可视化展示用。 */
        String description,
        /** 是否敏感字段：true 时该字段值在 trace 展示出口被读时脱敏（D71）；默认 false。 */
        @JsonSetter(nulls = Nulls.AS_EMPTY) boolean sensitive
) {
    /** 兼容既有 8 参调用点；sensitive 默认 false。新代码请用全参构造器。 */
    public PayloadFieldSpec(String name, String type, boolean required, List<Object> enumValues,
                            Double minimum, Double maximum, String pattern, String description) {
        this(name, type, required, enumValues, minimum, maximum, pattern, description, false);
    }
}
