package com.sstlfsj.rule.config.internal.domain;

/**
 * rule_definition.status 封闭取值。
 * <p>枚举名 == DB varchar 字面量，由 MyBatis-Plus 全局 enum TypeHandler 按 {@code name()} 与 varchar 列往返映射；
 * 出 VO/DTO/API 契约边界时以 {@code name()} 转 String（对外契约保持 String）。</p>
 * <p>发布为同步单原子事务（D19）：成功 → PUBLISHED，失败 → 事务回滚保持原态，无中间态。
 * PUBLISHED ↔ DISABLED 为关停/启用切换。</p>
 */
public enum RuleDefinitionStatus {
    DRAFT, PUBLISHED, DISABLED
}
