package com.sstlfsj.rule.config.internal.event;

/**
 * 审计快照标记接口（typed 容器）：配置操作前/后快照的统一类型，
 * 各发布点用具体 record 实现承载结构化字段，集中监听器序列化为 audit_log 的 JSON 列。
 *
 * <p>用 marker 接口而非裸 Object，既避免手拼 JSON 字符串，又把序列化下沉到单一落库处。
 */
public sealed interface AuditSnapshot
        permits DraftCreatedSnapshot, RulePublishedSnapshot, RuleImportedSnapshot, MetricChangedSnapshot {
}
