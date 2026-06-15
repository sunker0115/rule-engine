package com.sstlfsj.rule.config.internal.event;

/**
 * 连接器变更快照：CREATE / UPDATE connector_definition 时记录连接器编码与展示名，落 audit_log 的 before/after_snapshot。
 *
 * @param connectorCode 连接器编码
 * @param name          连接器展示名
 */
public record ConnectorChangedSnapshot(String connectorCode, String name) implements AuditSnapshot {
}
