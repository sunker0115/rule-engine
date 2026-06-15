package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}
