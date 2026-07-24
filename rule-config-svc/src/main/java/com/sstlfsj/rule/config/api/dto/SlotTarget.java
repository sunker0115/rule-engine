package com.sstlfsj.rule.config.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** slot 绑定目标——sealed 多态，Jackson 按 type 判别。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = JsonPointerTarget.class, name = "JsonPointerTarget")
})
public sealed interface SlotTarget permits JsonPointerTarget {
}
