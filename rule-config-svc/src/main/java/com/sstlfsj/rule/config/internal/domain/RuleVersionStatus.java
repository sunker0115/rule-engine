package com.sstlfsj.rule.config.internal.domain;

/**
 * rule_version.status 封闭取值。
 * <p>枚举名 == DB varchar 字面量，由 MyBatis-Plus 全局 enum TypeHandler 按 {@code name()} 与 varchar 列往返映射；
 * 出 VO/DTO/API 契约边界时以 {@code name()} 转 String（对外契约保持 String）。</p>
 * <p>DRAFT 为发布前草稿行状态，发布后生成 ACTIVE 行，旧 ACTIVE 行转 SUPERSEDED。</p>
 */
public enum RuleVersionStatus {
    DRAFT, ACTIVE, SUPERSEDED, DISABLED
}
