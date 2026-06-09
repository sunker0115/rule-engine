package com.sstlfsj.rule.config.internal.event;

/**
 * 规则发布快照：PUBLISH 时记录新生效的规则版本 id 与版本号。
 *
 * @param ruleVersionId 新生效规则版本 id
 * @param version       版本号
 */
public record RulePublishedSnapshot(Long ruleVersionId, long version) implements AuditSnapshot {
}
