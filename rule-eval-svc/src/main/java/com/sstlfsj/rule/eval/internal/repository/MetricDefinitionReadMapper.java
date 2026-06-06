package com.sstlfsj.rule.eval.internal.repository;

import com.sstlfsj.rule.eval.internal.domain.MetricDefinitionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只读 Mapper：按 tenant + metricCode 查 ACTIVE metric_definition。 */
@Mapper
public interface MetricDefinitionReadMapper {

    /**
     * 查询单个 ACTIVE metric 定义。
     *
     * @param tenantId   租户 id
     * @param metricCode 指标编码
     * @return 行；不存在返回 null
     */
    @Select("""
            SELECT metric_code       AS metricCode,
                   source_type       AS sourceType,
                   data_type         AS dataType,
                   allow_provided    AS allowProvided,
                   cache_ttl_seconds AS cacheTtlSeconds,
                   params            AS paramsJson
            FROM metric_definition
            WHERE tenant_id = #{tenantId} AND metric_code = #{metricCode} AND status = 'ACTIVE'
            """)
    MetricDefinitionRow findActive(@Param("tenantId") long tenantId,
                                   @Param("metricCode") String metricCode);
}
