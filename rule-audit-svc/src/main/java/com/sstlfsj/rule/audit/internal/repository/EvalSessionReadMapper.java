package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.audit.internal.domain.EvalSessionRow;
import com.sstlfsj.rule.audit.internal.domain.RuleSessionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** evaluation_session 只读 Mapper（audit-svc 自有，不共享 eval-svc 的 internal）。 */
@Mapper
public interface EvalSessionReadMapper extends BaseMapper<EvalSessionRow> {

    /** 评估会话分页：按租户过滤，eventId 非空时附加条件，按开始时间倒序。 */
    default Page<EvalSessionRow> selectEvalSessionPage(Page<EvalSessionRow> page,
                                                       Long tenantId, String eventId) {
        return selectPage(page, new LambdaQueryWrapper<EvalSessionRow>()
                .eq(EvalSessionRow::getTenantId, tenantId)
                .eq(eventId != null, EvalSessionRow::getEventId, eventId)
                .orderByDesc(EvalSessionRow::getStartedAt));
    }

    /**
     * 按规则定义 ID 查询历史评估会话（JOIN node_trace + rule_version）。
     *
     * @param ruleDefinitionId 规则定义 ID
     * @param status           可选状态过滤（null 表示不过滤）
     * @param limit            每页条数
     * @param offset           偏移量
     * @return 匹配的会话列表，按 started_at 倒序
     */
    List<RuleSessionRow> selectByRuleDefinitionId(
            @Param("ruleDefinitionId") Long ruleDefinitionId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /**
     * 统计按规则定义 ID 过滤的历史评估会话总数。
     *
     * @param ruleDefinitionId 规则定义 ID
     * @param status           可选状态过滤（null 表示不过滤）
     * @return 匹配的会话总数
     */
    long countByRuleDefinitionId(
            @Param("ruleDefinitionId") Long ruleDefinitionId,
            @Param("status") String status);
}
