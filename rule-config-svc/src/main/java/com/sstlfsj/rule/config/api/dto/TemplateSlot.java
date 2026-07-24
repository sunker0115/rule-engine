package com.sstlfsj.rule.config.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sstlfsj.rule.kernel.api.model.DataType;

/**
 * 模板 Slot 参数定义。
 * 无 defaultValue 字段——默认值 = bodySkeleton 在该 slot 对应 binding 位置的当前值。
 * {@link #required} = true 表示实例化时必须提供值，skeleton 值仅保证可预览。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplateSlot(
        String key,
        String label,
        DataType dataType,
        boolean required,
        SlotConstraint constraint
) {
    public TemplateSlot {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("slot key 不能为空");
        if (dataType == null) throw new IllegalArgumentException("slot dataType 不能为空");
        if (dataType == DataType.UNKNOWN) throw new IllegalArgumentException("slot dataType 不能为 UNKNOWN");
    }
}
