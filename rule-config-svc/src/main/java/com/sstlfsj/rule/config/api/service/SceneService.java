package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.dto.SceneListItem;
import com.sstlfsj.rule.config.api.dto.UpdateSceneCommand;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 场景生命周期管理：创建、更新、查询、禁用。 */
public interface SceneService {

    /**
     * 查询租户场景（精简列表，供前端场景选择器 / 列表页）。
     *
     * @param tenantId 租户 ID
     * @param status   可选状态过滤（null 或空表示不过滤，返回全部）
     * @return 场景精简列表
     */
    List<SceneListItem> listScenes(Long tenantId, String status);

    /**
     * 为指定租户创建新场景（含 D13 元数据）。
     *
     * @param tenantId          租户 ID
     * @param sceneCode         租户内唯一的场景编码
     * @param name              场景展示名称
     * @param description       场景业务说明（可为 null）
     * @param dominantMode      PUSH / PULL / HYBRID（null 时默认 PUSH）
     * @param subjectType       USER / ACCOUNT / DEVICE / ORDER / CUSTOM（null 时默认 USER）
     * @param eventTypes        允许的 eventType 白名单（null 时默认空）
     * @param payloadSchema     payloadSchema 字段声明（null 表示暂不设置）
     * @param defaultParams     默认参数（开放结构，null 表示暂不设置）
     * @param actorId           创建操作人 ID
     * @return 新创建场景的 ID
     */
    Long createScene(Long tenantId, String sceneCode, String name,
                     String description, String dominantMode, String subjectType,
                     List<String> eventTypes, List<PayloadFieldSpec> payloadSchema,
                     Map<String, Object> defaultParams, String actorId);

    /**
     * 更新已有场景元数据。payloadSchema 发生变化时自动快照历史版本并自增版本号。
     *
     * @param cmd 更新命令，字段为 null 表示不更新该项
     */
    void updateScene(UpdateSceneCommand cmd);

    /**
     * 查询场景详情（含 payloadSchema / eventTypes 等 D13 字段）。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return 场景详情 DTO
     */
    SceneDetailDto getScene(Long tenantId, String sceneCode);

    /**
     * 禁用场景，禁用后不再参与规则评估匹配。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 待禁用的场景编码
     * @param actorId   禁用操作人 ID
     */
    void disableScene(Long tenantId, String sceneCode, String actorId);

    /**
     * 启/禁用场景。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @param enable    true 启用，false 禁用
     */
    void toggleSceneStatus(Long tenantId, String sceneCode, boolean enable, String actorId);

    /**
     * 读时脱敏所需的 live 敏感集（D71）。
     *
     * @param payloadFields 该 scene payloadSchema 中 sensitive=true 的字段名集合
     * @param metricCodes   该租户 metric 定义中 sensitive=true 的 metric 码集合
     */
    record SensitiveRefs(Set<String> payloadFields, Set<String> metricCodes) {}

    /**
     * 查询指定 (租户, 场景) 的 live 敏感集，供 trace 展示出口读时脱敏。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return 敏感 payload 字段集 + 敏感 metric 码集；场景不存在抛 IllegalArgumentException
     */
    SensitiveRefs getSensitiveRefs(Long tenantId, String sceneCode);
}
