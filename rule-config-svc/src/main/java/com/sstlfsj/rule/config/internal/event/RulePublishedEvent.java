package com.sstlfsj.rule.config.internal.event;

/** 规则版本成功激活时发布的领域事件。 */
public record RulePublishedEvent(
        String tenantId,
        String sceneCode,
        Long ruleVersionId
) {}
