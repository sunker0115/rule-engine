package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.SlotResolutionContext;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;

/** Slot 引用解析器 SPI。按 kind 分派，加 kind = 加实现类，service 不改。 */
public interface SlotRefResolver {
    /** 支持的 slot 种类 */
    boolean supports(SlotKind kind);
    /** 验证引用有效性（Phase 1：仅存在性校验） */
    void validate(String value, TemplateSlot slot, SlotResolutionContext ctx);
}
