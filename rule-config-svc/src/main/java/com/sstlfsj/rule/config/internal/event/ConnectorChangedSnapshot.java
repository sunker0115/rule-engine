package com.sstlfsj.rule.config.internal.event;

/**
 * 连接器变更快照：CREATE / UPDATE / DISABLE connector_definition 时记录编码、展示名与状态，落 audit_log 的 before/after_snapshot。
 * status 区分前后态，使 DISABLE 等只改状态的操作在审计里能还原 ACTIVE→DISABLED 的变迁。
 *
 * @param connectorCode 连接器编码
 * @param name          连接器展示名
 * @param status        连接器状态名（ACTIVE / DISABLED）
 */
public record ConnectorChangedSnapshot(String connectorCode, String name, String status) implements AuditSnapshot {
}
