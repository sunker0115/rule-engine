package com.sstlfsj.rule.config.internal.domain;

/**
 * audit_log.action 取值：配置操作类型。
 *
 * <p>{@code name()} 即 audit_log.action 的 varchar 落库值（MyBatis-Plus 默认 enum TypeHandler 按 name 转换）。
 */
public enum AuditAction { CREATE, UPDATE, PUBLISH, ENABLE, DISABLE, DELETE, IMPORT }
