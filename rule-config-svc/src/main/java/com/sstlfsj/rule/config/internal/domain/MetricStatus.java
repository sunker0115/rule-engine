package com.sstlfsj.rule.config.internal.domain;

/**
 * metric_definition.status 封闭取值。
 * <p>枚举名 == DB varchar 字面量，由 MyBatis-Plus 全局 enum TypeHandler 按 {@code name()} 与 varchar 列往返映射；
 * 出 VO/DTO/API 契约边界时以 {@code name()} 转 String（对外契约保持 String）。
 * 升版时旧行转 SUPERSEDED，新行为 ACTIVE。</p>
 */
public enum MetricStatus {
    ACTIVE, SUPERSEDED, DISABLED
}
