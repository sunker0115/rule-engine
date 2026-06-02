package com.sstlfsj.rule.config.internal.event;

/** Published when a rule version is successfully activated. */
public record RulePublishedEvent(
        String tenantId,
        String sceneCode,
        Long ruleVersionId
) {}
