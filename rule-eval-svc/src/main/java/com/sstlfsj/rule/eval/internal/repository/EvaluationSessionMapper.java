package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/** evaluation_session 表 CRUD Mapper。 */
@Mapper
public interface EvaluationSessionMapper extends BaseMapper<EvaluationSession> {

    /** 按 (tenantId, eventId) 查会话（幂等回查），不存在返回 null。 */
    default EvaluationSession findByTenantAndEvent(Long tenantId, String eventId) {
        return selectOne(new LambdaQueryWrapper<EvaluationSession>()
                .eq(EvaluationSession::getTenantId, tenantId)
                .eq(EvaluationSession::getEventId, eventId));
    }

    /**
     * 将 PENDING 会话按 id 更新为终态（HIT / MISS / ERROR），显式 set 各字段（含可为 null 的列）。
     * 用 LambdaUpdateWrapper 而非 updateById：errorCode / finalDecision 等需写入 null 值。
     */
    default void markFinal(Long id, String status, String finalDecision, String hitDecisions,
                           String errorCode, Integer hitRuleCount, LocalDateTime finishedAt,
                           String contextSnapshot) {
        update(null, new LambdaUpdateWrapper<EvaluationSession>()
                .eq(EvaluationSession::getId, id)
                .set(EvaluationSession::getStatus, status)
                .set(EvaluationSession::getFinalDecision, finalDecision)
                .set(EvaluationSession::getHitDecisions, hitDecisions)
                .set(EvaluationSession::getErrorCode, errorCode)
                .set(EvaluationSession::getHitRuleCount, hitRuleCount)
                .set(EvaluationSession::getFinishedAt, finishedAt)
                .set(EvaluationSession::getContextSnapshot, contextSnapshot));
    }
}
