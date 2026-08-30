package com.sstlfsj.rule.config.api.dto;

/** Slot 解析上下文，携带调用方租户与场景信息 */
public record SlotResolutionContext(Long tenantId, String sceneCode) {}
