package com.sstlfsj.rule.config.api.service;

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

    /** metric 写入参数。 */
    record MetricWriteCommand(
            String name,
            String sourceType,
            String dataType,
            String paramsJson,
            Integer cacheTtlSeconds,
            boolean allowProvided) {}
}
