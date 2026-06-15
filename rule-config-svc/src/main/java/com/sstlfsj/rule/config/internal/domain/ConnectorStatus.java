package com.sstlfsj.rule.config.internal.domain;

/** 连接器生命周期状态（枚举名 == DB varchar 字面量，MyBatis-Plus 全局 enum TypeHandler 往返）。
 * 连接器不做 per-version 冻结，无 SUPERSEDED。 */
public enum ConnectorStatus {
    ACTIVE, DISABLED
}
