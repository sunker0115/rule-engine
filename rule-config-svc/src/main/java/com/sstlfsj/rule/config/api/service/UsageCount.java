package com.sstlfsj.rule.config.api.service;

/** 资源（decision/metric）code → 被 ACTIVE 规则引用计数。供列表徽标批量计数复用。 */
public record UsageCount(String code, int count) {}
