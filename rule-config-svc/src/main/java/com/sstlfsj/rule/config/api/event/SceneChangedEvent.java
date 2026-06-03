package com.sstlfsj.rule.config.api.event;

/** 场景激活状态变更后发布的 Modulith 事件。 */
public record SceneChangedEvent(
        String tenantId,
        String sceneCode,
        boolean active
) {}
