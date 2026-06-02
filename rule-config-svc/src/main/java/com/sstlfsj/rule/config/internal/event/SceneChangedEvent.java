package com.sstlfsj.rule.config.internal.event;

/** Published when a scene's active status changes. */
public record SceneChangedEvent(
        String tenantId,
        String sceneCode,
        boolean active
) {}
