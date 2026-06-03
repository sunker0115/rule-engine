package com.sstlfsj.rule.config.api.event;

/** 规则版本成功激活后发布的 Modulith 事件。 */
public record RulePublishedEvent(
        String tenantId,
        String sceneCode,
        Long ruleVersionId
) {}
