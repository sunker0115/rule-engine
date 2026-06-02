package com.sstlfsj.rule.config.api.service;

/** Manages scene lifecycle: creation, update, and disabling. */
public interface SceneService {

    /**
     * Creates a new scene for the given tenant.
     *
     * @param tenantId  tenant owning the scene
     * @param sceneCode unique scene code within the tenant
     * @param name      human-readable scene name
     * @param actorId   ID of the operator creating the scene
     * @return ID of the newly created scene
     */
    Long createScene(String tenantId, String sceneCode, String name, String actorId);

    /**
     * Updates the metadata of an existing scene.
     *
     * @param tenantId  tenant owning the scene
     * @param sceneCode scene to update
     * @param actorId   ID of the operator making the update
     */
    void updateScene(String tenantId, String sceneCode, String actorId);

    /**
     * Disables a scene, preventing new evaluations from matching it.
     *
     * @param tenantId  tenant owning the scene
     * @param sceneCode scene to disable
     * @param actorId   ID of the operator disabling the scene
     */
    void disableScene(String tenantId, String sceneCode, String actorId);
}
