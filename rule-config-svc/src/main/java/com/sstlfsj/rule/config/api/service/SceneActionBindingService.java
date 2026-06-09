package com.sstlfsj.rule.config.api.service;

import java.util.List;

/**
 * Scene action 绑定（白名单）写服务（10-api-contract /admin/v1/scenes/{sceneCode}/action-bindings）。
 *
 * <p>scene_action_binding 是 Scene 可用 actionType 白名单。写后发布 {@code SceneChangedEvent}
 * 使 eval 侧 SceneActionBindingIndex 热刷新（无需重启），事件 active 取场景真实状态。
 */
public interface SceneActionBindingService {

    /**
     * 列出场景当前的全部 action 绑定。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return 绑定列表，无绑定时为空列表
     */
    List<SceneActionBindingItem> list(String tenantId, String sceneCode);

    /**
     * 整组覆盖式保存场景的 action 绑定：单事务内删除 {@code items} 缺失项、upsert {@code items} 内项，
     * 写审计并发布 {@code SceneChangedEvent}（active=场景是否 ACTIVE）触发索引热刷新。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @param items     目标绑定全量集合（actionType 不可重复）
     * @param actorId   操作人 ID
     * @throws IllegalArgumentException 场景不存在，或 items 内 actionType 重复
     */
    void replace(String tenantId, String sceneCode, List<SceneActionBindingItem> items, String actorId);

    /**
     * action 绑定项。defaultParamsJson / rateLimitOverrideJson 为 JSON 对象字符串，可空。
     *
     * @param actionType            actionType 路由键
     * @param defaultParamsJson     Scene 级默认参数 JSON，可空
     * @param rateLimitOverrideJson Scene 级频控覆盖 JSON，可空
     */
    record SceneActionBindingItem(String actionType, String defaultParamsJson, String rateLimitOverrideJson) {}
}
