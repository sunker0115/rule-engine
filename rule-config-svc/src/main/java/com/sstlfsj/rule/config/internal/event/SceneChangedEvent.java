package com.sstlfsj.rule.config.internal.event;

/** 场景激活状态变更时发布的领域事件。 */
public record SceneChangedEvent(
        String tenantId,
        String sceneCode,
        boolean active
) {}
