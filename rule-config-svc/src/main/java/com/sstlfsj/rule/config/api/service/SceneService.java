package com.sstlfsj.rule.config.api.service;

/** Manages scene lifecycle: creation, update, and disabling. */
public interface SceneService {
    Long createScene(String tenantId, String sceneCode, String name, String actorId);
    void updateScene(String tenantId, String sceneCode, String actorId);
    void disableScene(String tenantId, String sceneCode, String actorId);
}
