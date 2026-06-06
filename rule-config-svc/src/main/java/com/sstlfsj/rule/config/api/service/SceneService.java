package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.dto.SceneListItem;

import java.util.List;

/** 场景生命周期管理：创建、更新、查询、禁用。 */
public interface SceneService {

    /**
     * 查询租户全部场景（精简列表，供前端场景选择器 / 列表页）。
     *
     * @param tenantId 租户 ID
     * @return 场景精简列表
     */
    List<SceneListItem> listScenes(String tenantId);

    /**
     * 为指定租户创建新场景（含 D13 元数据）。
     *
     * @param tenantId          租户 ID
     * @param sceneCode         租户内唯一的场景编码
     * @param name              场景展示名称
     * @param description       场景业务说明（可为 null）
     * @param dominantMode      PUSH / PULL / HYBRID（null 时默认 PUSH）
     * @param subjectType       USER / ACCOUNT / DEVICE / ORDER / CUSTOM（null 时默认 USER）
     * @param eventTypesJson    允许的 eventType 白名单 JSON 数组字符串（null 时默认 "[]"）
     * @param payloadSchemaJson payloadSchema JSON 数组字符串（null 表示暂不设置）
     * @param defaultParamsJson 默认参数 JSON 对象字符串（null 表示暂不设置）
     * @param actorId           创建操作人 ID
     * @return 新创建场景的 ID
     */
    Long createScene(String tenantId, String sceneCode, String name,
                     String description, String dominantMode, String subjectType,
                     String eventTypesJson, String payloadSchemaJson, String defaultParamsJson,
                     String actorId);

    /**
     * 更新已有场景元数据。payloadSchema 发生变化时自动快照历史版本并自增版本号。
     *
     * @param tenantId          租户 ID
     * @param sceneCode         待更新的场景编码
     * @param name              新名称（null 表示不更新）
     * @param eventTypesJson    新 eventType 白名单（null 表示不更新）
     * @param payloadSchemaJson 新 payloadSchema（null 表示不更新）
     * @param defaultParamsJson 新 defaultParams（null 表示不更新）
     * @param actorId           更新操作人 ID
     */
    void updateScene(String tenantId, String sceneCode,
                     String name, String eventTypesJson,
                     String payloadSchemaJson, String defaultParamsJson,
                     String actorId);

    /**
     * 查询场景详情（含 payloadSchema / eventTypes 等 D13 字段）。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return 场景详情 DTO
     */
    SceneDetailDto getScene(String tenantId, String sceneCode);

    /**
     * 禁用场景，禁用后不再参与规则评估匹配。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 待禁用的场景编码
     * @param actorId   禁用操作人 ID
     */
    void disableScene(String tenantId, String sceneCode, String actorId);
}
