package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.MetricListQuery;
import com.sstlfsj.rule.config.api.dto.MetricListItemVO;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;

/** 场景元数据查询：可用条件类型及指标列表。 */
public interface MetadataService {

    /** 启/禁 metric。 */
    void toggleMetricStatus(Long tenantId, String metricCode, boolean enable);

    /**
     * 返回指定租户的 metric 运行时定义列表，供嵌入式 SDK 下发（仅元数据，不含凭证）。
     *
     * @param tenantId 租户 ID
     * @param scenes   场景编码列表；v1 暂不按场景白名单过滤，传空列表即可
     * @return MetricDescriptor 列表（含 params/cacheTtl/allowProvided）
     */
    /** 查询租户全部 metric 运行时定义（对外接口，保留简单参数）。 */
    default java.util.List<MetricDescriptor> listMetricDefinitions(Long tenantId, java.util.List<String> scenes) {
        return listMetricDefinitions(new MetricListQuery(tenantId, scenes));
    }

    /** 查询租户 metric 运行时定义（内部使用，Query 收口）。 */
    java.util.List<MetricDescriptor> listMetricDefinitions(MetricListQuery q);

    /** 管理后台：查询租户 metric 完整信息（含 name/status/时间/tenantId）。 */
    java.util.List<MetricListItemVO> listMetricItems(Long tenantId);

    /**
     * 管理后台：查询租户内单个 metric 完整定义，供前端编辑器直接加载。
     *
     * @param tenantId   租户 ID
     * @param metricCode metric 编码
     * @return metric 列表项 VO；不存在抛 {@link IllegalArgumentException}
     */
    MetricListItemVO getMetricItem(Long tenantId, String metricCode);

    /**
     * 返回指定场景的元数据，包括可用条件类型和指标列表。
     *
     * @param tenantId  场景所属租户 ID
     * @param sceneCode 待查询的场景编码
     * @return 包含条件/指标类型列表的元数据响应
     */
    MetadataResponse getSceneMetadata(Long tenantId, String sceneCode);

    /**
     * 查场景输入参数清单：该场景所有 ACTIVE 规则引用的 payload 字段并集
     * （eventType 非空则收窄到会被该事件类型触发的规则）。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @param eventType 事件类型；非空时仅纳入 triggerEventTypes 含该值或为空（通配）的规则，null/空白表示不收窄
     * @return 输入字段并集（按 name 去重，保持首次出现顺序）
     */
    InputManifestResponse getInputManifest(Long tenantId, String sceneCode, String eventType);

    record MetadataResponse(
            java.util.List<OperatorSpec> conditionTypes,
            java.util.List<MetricMeta> availableMetrics,
            java.util.List<String> eventTypes,
            java.util.List<String> expressionLangs
    ) {}

    /** 输入清单响应（对外契约；字段值与发布期冻结的 PayloadDependency 同形）。 */
    record InputManifestResponse(java.util.List<InputFieldSpec> fields) {}

    /** 单个输入字段契约：名 + 类型标签 + 是否必填。 */
    record InputFieldSpec(String name, String dataType, boolean required) {}

    record MetricMeta(String metricCode, String name,
                      String dataType, String sourceType, boolean allowProvided) {}
}
