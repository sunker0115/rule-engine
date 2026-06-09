package com.sstlfsj.rule.config.internal.domain;

/**
 * rule_definition.status 封闭取值。
 * <p>枚举名 == DB ENUM 字面量；当前由 app 层以 {@code name()} 与 varchar 列往返映射。</p>
 */
public enum RuleDefinitionStatus {
    DRAFT, PUBLISHED, DISABLED
}
