package com.sstlfsj.rule.config.api.service;

import java.util.List;
import java.util.Map;

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
     * action 绑定项。defaultParams / rateLimitOverride 为 JSON 对象（{@code Map<String,Object>}），可空；
     * 与实体间的 JSON 串序列化由 service 实现承担，对外只暴露类型化对象。
     *
     * @param actionType        actionType 路由键
     * @param defaultParams     Scene 级默认参数（依 actionType 异构，故为开放 Map），可空
     * @param rateLimitOverride Scene 级频控覆盖（频控功能未实装，暂为开放 Map），可空
     */
    record SceneActionBindingItem(String actionType,
                                  Map<String, Object> defaultParams,
                                  Map<String, Object> rateLimitOverride) {}
}
