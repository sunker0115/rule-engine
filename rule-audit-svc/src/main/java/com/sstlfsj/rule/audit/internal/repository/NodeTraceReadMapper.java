package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.NodeTraceRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** node_trace 只读 Mapper。 */
@Mapper
public interface NodeTraceReadMapper extends BaseMapper<NodeTraceRow> {

    /** 查指定 (会话, 租户) 的节点追踪，按 node_path 字典序升序。 */
    default List<NodeTraceRow> findBySessionAndTenant(Long evaluationSessionId, Long tenantId) {
        return selectList(new LambdaQueryWrapper<NodeTraceRow>()
                .eq(NodeTraceRow::getEvaluationSessionId, evaluationSessionId)
                .eq(NodeTraceRow::getTenantId, tenantId)
                .orderByAsc(NodeTraceRow::getNodePath));
    }
}
