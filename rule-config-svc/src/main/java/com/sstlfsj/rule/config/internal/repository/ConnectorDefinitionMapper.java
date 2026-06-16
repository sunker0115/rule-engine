package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.internal.domain.ConnectorDefinition;
import com.sstlfsj.rule.config.internal.domain.ConnectorStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 连接器定义单表查询（BaseMapper + default 封装，不在 service 散拼 wrapper）。 */
@Mapper
public interface ConnectorDefinitionMapper extends BaseMapper<ConnectorDefinition> {

    /** 取租户内某连接器（任意状态），null 表示不存在。 */
    default ConnectorDefinition findByCode(Long tenantId, String connectorCode) {
        return selectOne(new LambdaQueryWrapper<ConnectorDefinition>()
                .eq(ConnectorDefinition::getTenantId, tenantId)
                .eq(ConnectorDefinition::getConnectorCode, connectorCode));
    }

    /** 取租户内全部 ACTIVE 连接器。 */
    default List<ConnectorDefinition> findActiveByTenant(Long tenantId) {
        return selectList(new LambdaQueryWrapper<ConnectorDefinition>()
                .eq(ConnectorDefinition::getTenantId, tenantId)
                .eq(ConnectorDefinition::getStatus, ConnectorStatus.ACTIVE));
    }

    /** 取所有租户的全部 ACTIVE 连接器（tenantId 为 null 时列表页全量展示）。 */
    default List<ConnectorDefinition> findAllActive() {
        return selectList(new LambdaQueryWrapper<ConnectorDefinition>()
                .eq(ConnectorDefinition::getStatus, ConnectorStatus.ACTIVE));
    }

    /**
     * 分页查询连接器，支持租户/关键词/状态过滤。
     * tenantId 为 null 不按租户过滤；keyword 模糊匹配编码或名称；status 为 null 不过滤状态。
     */
    default Page<ConnectorDefinition> searchPage(Page<ConnectorDefinition> page,
                                                  Long tenantId, String keyword, String status) {
        LambdaQueryWrapper<ConnectorDefinition> w = new LambdaQueryWrapper<>();
        if (tenantId != null) w.eq(ConnectorDefinition::getTenantId, tenantId);
        if (keyword != null && !keyword.isBlank())
            w.and(q -> q.like(ConnectorDefinition::getConnectorCode, keyword)
                         .or().like(ConnectorDefinition::getName, keyword));
        if (status != null && !status.isBlank())
            w.eq(ConnectorDefinition::getStatus, ConnectorStatus.valueOf(status));
        return selectPage(page, w);
    }
}
