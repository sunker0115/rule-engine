package com.sstlfsj.rule.config.api.service;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.List;
import java.util.Map;

/** Metric 注册 / 更新 / 升版写服务（10-api-contract §3 /api/v1/metrics）。 */
public interface MetricWriteService {

    /** 注册新 metric（version=1, status=ACTIVE）。返回新行 id。 */
    Long create(Long tenantId, String metricCode, MetricWriteCommand cmd, String actorId);

    /**
     * 更新 metric。breakingChange=true 时语义不兼容：旧 ACTIVE 行转 SUPERSEDED + 插入新版本行；
     * false 时原地更新当前 ACTIVE 行。返回当前生效行的 version。
     * 无 ACTIVE 行时抛 IllegalArgumentException。
     */
    int update(Long tenantId, String metricCode, MetricWriteCommand cmd,
               boolean breakingChange, String actorId);

    /** 查询引用某 (metricCode, version) 的所有 ACTIVE 规则（运营升版前评估影响面）。 */
    List<RuleRef> findReferencingRules(Long tenantId, String metricCode, int metricVersion);

    /**
     * 查询引用某 metric 的所有 ACTIVE 规则（版本无关，跨全部版本聚合）。usages / 血缘默认视图。
     * 版本特定的影响面用 {@link #findReferencingRules}。
     *
     * @param tenantId   租户 id
     * @param metricCode metric 编码
     * @return 引用该 metric 的规则引用项（按 ruleDefinition 去重）；无引用返回空列表
     */
    List<RuleRef> findRulesReferencingMetric(Long tenantId, String metricCode);

    /**
     * 一次扫聚合 tenant 下每个 metricCode 的 ACTIVE 规则引用计数（版本无关，列表徽标用）。
     *
     * @param tenantId 租户 id
     * @return 每个 metricCode 的引用计数
     */
    List<UsageCount> countRuleUsages(Long tenantId);

    /** metric 写入参数。params 为结构依 sourceType 而异的 JSON 对象（前端直接传对象，服务端序列化存库）。 */
    record MetricWriteCommand(
            String name,
            String sourceType,
            String dataType,
            Map<String, Object> params,
            Integer cacheTtlSeconds,
            boolean allowProvided,
            /**
             * 是否敏感 metric：true 时其值在 trace 展示出口被读时脱敏（D71，租户级 D54）；默认 false。
             * 请求体缺该键时落 false（Jackson 3 FAIL_ON_NULL_FOR_PRIMITIVES 兜底，与 PayloadFieldSpec.sensitive 一致）。
             */
            @JsonSetter(nulls = Nulls.AS_EMPTY) boolean sensitive) {

        /** 兼容既有 6 参调用点；sensitive 默认 false。 */
        public MetricWriteCommand(String name, String sourceType, String dataType,
                                  Map<String, Object> params, Integer cacheTtlSeconds, boolean allowProvided) {
            this(name, sourceType, dataType, params, cacheTtlSeconds, allowProvided, false);
        }
    }

    /**
     * 引用某 metric 版本的规则引用项。sceneCode 由 rule_definition.scene_id 关联 scene 表；
     * status 为 rule_definition.status（口径：按 rv.status=ACTIVE 收集，不按 rd.status 过滤，
     * 故 rd.status=DISABLED 但 rv.status=ACTIVE 的规则仍会出现）。
     */
    record RuleRef(Long ruleDefinitionId, String ruleCode, String ruleName,
                   String sceneCode, String status) {}
}
