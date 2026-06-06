package com.sstlfsj.rule.config.api.service;

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

    /** metric 写入参数。params 为结构依 sourceType 而异的 JSON 对象（前端直接传对象，服务端序列化存库）。 */
    record MetricWriteCommand(
            String name,
            String sourceType,
            String dataType,
            Map<String, Object> params,
            Integer cacheTtlSeconds,
            boolean allowProvided) {}

    /**
     * 引用某 metric 版本的规则引用项。sceneCode 由 rule_definition.scene_id 关联 scene 表；
     * status 为 rule_definition.status（口径：按 rv.status=ACTIVE 收集，不按 rd.status 过滤，
     * 故 rd.status=DISABLED 但 rv.status=ACTIVE 的规则仍会出现）。
     */
    record RuleRef(Long ruleDefinitionId, String ruleCode, String ruleName,
                   String sceneCode, String status) {}
}
