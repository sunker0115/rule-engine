package com.sstlfsj.rule.config.api.dto;

/**
 * JSON Pointer 寻址 body skeleton 内具体位置。
 *
 * @param jsonPointer RFC 6901 JsonPointer 字符串（如 /conditionAst/children/0/params/threshold）
 */
public record JsonPointerTarget(String jsonPointer) implements SlotTarget {
}
