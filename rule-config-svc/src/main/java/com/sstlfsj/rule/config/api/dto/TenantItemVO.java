package com.sstlfsj.rule.config.api.dto;

/**
 * 租户下拉列表项。
 *
 * @param id   租户 ID
 * @param code 租户业务标识
 * @param name 租户显示名称
 */
public record TenantItemVO(Long id, String code, String name) {}
