package com.sstlfsj.rule.config.internal.event;

/**
 * 规则状态快照：记录某时点规则定义的 status 与当前生效版本，用于 DISABLE / PUBLISH 的 before 快照，
 * 让 audit_log 能还原"变更前规则是什么状态"。
 *
 * @param ruleDefinitionId 规则定义 id
 * @param status           规则状态（DRAFT / PUBLISHED / DISABLED 等的 name）
 * @param currentVersion   当前生效 rule_version id（首次发布前可能为 null）
 */
public record RuleStatusSnapshot(Long ruleDefinitionId, String status, Long currentVersion)
        implements AuditSnapshot {
}
