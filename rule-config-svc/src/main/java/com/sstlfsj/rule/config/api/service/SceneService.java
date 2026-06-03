package com.sstlfsj.rule.config.api.service;

/** 场景生命周期管理：创建、更新、禁用。 */
public interface SceneService {

    /**
     * 为指定租户创建新场景。
     *
     * @param tenantId  场景所属租户 ID
     * @param sceneCode 租户内唯一的场景编码
     * @param name      场景展示名称
     * @param actorId   创建操作人 ID
     * @return 新创建场景的 ID
     */
    Long createScene(String tenantId, String sceneCode, String name, String actorId);

    /**
     * 更新已有场景的元数据。
     *
     * @param tenantId  场景所属租户 ID
     * @param sceneCode 待更新的场景编码
     * @param actorId   更新操作人 ID
     */
    void updateScene(String tenantId, String sceneCode, String actorId);

    /**
     * 禁用场景，禁用后该场景不再参与规则评估匹配。
     *
     * @param tenantId  场景所属租户 ID
     * @param sceneCode 待禁用的场景编码
     * @param actorId   禁用操作人 ID
     */
    void disableScene(String tenantId, String sceneCode, String actorId);
}
