package com.sstlfsj.rule.config.internal.event;

/**
 * 规则草稿创建快照：CREATE 草稿时记录新建的规则定义与版本 id。
 *
 * @param ruleDefinitionId 新建规则定义 id
 * @param ruleVersionId    新建规则版本 id
 */
public record DraftCreatedSnapshot(Long ruleDefinitionId, Long ruleVersionId) implements AuditSnapshot {
}
