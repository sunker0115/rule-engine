package com.sstlfsj.rule.eval.internal.repository;

import com.sstlfsj.rule.eval.internal.domain.MetricDefinitionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只读 Mapper：按 tenant + metricCode + version 查 metric_definition（不限 status，SUPERSEDED 旧版可解析）。 */
@Mapper
public interface MetricDefinitionReadMapper {

    /**
     * 按精确版本查询 metric 定义（不限 status，使 SUPERSEDED 旧版快照仍可解析）。
     *
     * @param tenantId   租户 id
     * @param metricCode 指标编码
     * @param version    版本号
     * @return 行；不存在返回 null
     */
    @Select("""
            SELECT metric_code       AS metricCode,
                   version           AS version,
                   source_type       AS sourceType,
                   data_type         AS dataType,
                   allow_provided    AS allowProvided,
                   cache_ttl_seconds AS cacheTtlSeconds,
                   params            AS paramsJson
            FROM metric_definition
            WHERE tenant_id = #{tenantId} AND metric_code = #{metricCode} AND version = #{version}
            """)
    MetricDefinitionRow findByVersion(@Param("tenantId") long tenantId,
                                      @Param("metricCode") String metricCode,
                                      @Param("version") int version);
}
