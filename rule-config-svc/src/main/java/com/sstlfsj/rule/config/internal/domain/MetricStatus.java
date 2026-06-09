package com.sstlfsj.rule.config.internal.domain;

/**
 * metric_definition.status 封闭取值。
 * <p>枚举名 == DB ENUM 字面量；当前由 app 层以 {@code name()} 与 varchar 列往返映射。
 * 升版时旧行转 SUPERSEDED，新行为 ACTIVE。</p>
 */
public enum MetricStatus {
    ACTIVE, SUPERSEDED, DISABLED
}
