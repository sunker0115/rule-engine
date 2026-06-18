package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.ConnectorDefinitionRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 连接器只读查询（eval 侧）。 */
@Mapper
public interface ConnectorDefinitionReadMapper extends BaseMapper<ConnectorDefinitionRow> {

    /**
     * 取租户内某 ACTIVE 连接器。
     *
     * @param tenantId      租户 id
     * @param connectorCode 连接器编码
     * @return 行；不存在返回 null
     */
    default ConnectorDefinitionRow findActive(Long tenantId, String connectorCode) {
        return selectOne(new LambdaQueryWrapper<ConnectorDefinitionRow>()
                .eq(ConnectorDefinitionRow::getTenantId, tenantId)
                .eq(ConnectorDefinitionRow::getConnectorCode, connectorCode)
                .eq(ConnectorDefinitionRow::getStatus, "ACTIVE"));
    }

    /**
     * 取租户内全部 ACTIVE 连接器编码（供发布期引用闭合校验）。
     *
     * @param tenantId 租户 id
     * @return ACTIVE 连接器编码列表
     */
    default List<String> findActiveCodes(Long tenantId) {
        return selectList(new LambdaQueryWrapper<ConnectorDefinitionRow>()
                .select(ConnectorDefinitionRow::getConnectorCode)
                .eq(ConnectorDefinitionRow::getTenantId, tenantId)
                .eq(ConnectorDefinitionRow::getStatus, "ACTIVE"))
                .stream().map(ConnectorDefinitionRow::getConnectorCode).toList();
    }
}
