package com.sstlfsj.rule.config.internal.domain;

/**
 * rule_version.status 封闭取值。
 * <p>枚举名 == DB ENUM 字面量；当前由 app 层以 {@code name()} 与 varchar 列往返映射。
 * DRAFT 为发布前草稿行状态，发布后生成 ACTIVE 行，旧 ACTIVE 行转 SUPERSEDED。</p>
 */
public enum RuleVersionStatus {
    DRAFT, ACTIVE, SUPERSEDED
}
