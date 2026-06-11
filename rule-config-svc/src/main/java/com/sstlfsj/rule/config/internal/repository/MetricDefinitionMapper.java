package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

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

    /** 按 (tenantId) 查全部 ACTIVE metric。 */
    default List<MetricDefinition> findActiveByTenant(Long tenantId) {
        return selectList(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getTenantId, tenantId)
                .eq(MetricDefinition::getStatus, MetricStatus.ACTIVE));
    }

    /** 按 (tenantId, metricCode) 查 ACTIVE 版本，不存在返回 null。 */
    default MetricDefinition findActiveByCode(Long tenantId, String metricCode) {
        return selectOne(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getTenantId, tenantId)
                .eq(MetricDefinition::getMetricCode, metricCode)
                .eq(MetricDefinition::getStatus, MetricStatus.ACTIVE));
    }

    /** 按 (tenantId) + code 集合查 ACTIVE metric；空集合返回空列表。 */
    default List<MetricDefinition> findActiveByCodes(Long tenantId, Collection<String> metricCodes) {
        if (metricCodes == null || metricCodes.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getTenantId, tenantId)
                .in(MetricDefinition::getMetricCode, metricCodes)
                .eq(MetricDefinition::getStatus, MetricStatus.ACTIVE));
    }
}
