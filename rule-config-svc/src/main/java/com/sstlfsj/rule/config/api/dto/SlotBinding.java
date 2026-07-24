package com.sstlfsj.rule.config.api.dto;

/**
 * slot→body 位置的显式绑定（sidecar，非 token）。
 *
 * @param slotKey 对应 TemplateSlot.key
 * @param target  绑定的 body 位置
 */
public record SlotBinding(String slotKey, SlotTarget target) {
}
