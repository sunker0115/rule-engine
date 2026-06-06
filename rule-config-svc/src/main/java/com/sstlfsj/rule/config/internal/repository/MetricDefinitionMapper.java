package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import org.apache.ibatis.annotations.Mapper;

/** metric_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface MetricDefinitionMapper extends BaseMapper<MetricDefinition> {

    /** 按精确 (tenantId, metricCode, version) 查 metric，不存在返回 null。 */
    default MetricDefinition findByCodeAndVersion(Long tenantId, String metricCode, Integer version) {
        return selectOne(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getTenantId, tenantId)
                .eq(MetricDefinition::getMetricCode, metricCode)
                .eq(MetricDefinition::getVersion, version));
    }

    /** 按 (tenantId, metricCode) 查任意一行（判断是否已存在），不存在返回 null。 */
    default MetricDefinition findAnyByCode(Long tenantId, String metricCode) {
        return selectOne(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getTenantId, tenantId)
                .eq(MetricDefinition::getMetricCode, metricCode)
                .last("LIMIT 1"));
    }
}
