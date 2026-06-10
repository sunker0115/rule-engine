package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** evaluation_session 表 Mapper。AuditPersister 异步消费侧用 {@link #insertBatch} 多行批量落库（单次往返/单次 fsync）。 */
@Mapper
public interface EvaluationSessionMapper extends BaseMapper<EvaluationSession> {

    /**
     * 多行批量 INSERT 终态 session（id 为客户端 snowflake，须显式带入）；撞 uk_tenant_event 的重复
     * eventId 经 ON DUPLICATE KEY 空更新跳过（同 eventId 重复评估保留首条，best-effort）。
     *
     * @param list 待落库的终态 session（非空）
     * @return 影响行数
     */
    @Insert("""
            <script>
            INSERT INTO evaluation_session
              (id, tenant_id, event_id, scene_code, event_type, subject_id, source, mode, status,
               final_decision, hit_decisions, blocked_by, error_code, candidate_rule_count,
               hit_rule_count, score, category, occurred_at, started_at, finished_at, context_snapshot,
               eval_duration_ms)
            VALUES
            <foreach collection="list" item="s" separator=",">
              (#{s.id}, #{s.tenantId}, #{s.eventId}, #{s.sceneCode}, #{s.eventType}, #{s.subjectId},
               #{s.source}, #{s.mode}, #{s.status}, #{s.finalDecision}, #{s.hitDecisions}, #{s.blockedBy},
               #{s.errorCode}, #{s.candidateRuleCount}, #{s.hitRuleCount}, #{s.score}, #{s.category},
               #{s.occurredAt}, #{s.startedAt}, #{s.finishedAt}, #{s.contextSnapshot}, #{s.evalDurationMs})
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatch(@Param("list") List<EvaluationSession> list);

    /** 删 started_at 早于 cutoff 的行，单次最多 batchSize 条（分批短事务）。返回删除行数。 */
    default int purgeOlderThan(LocalDateTime cutoff, int batchSize) {
        // batchSize 为常量 int，无注入风险
        return delete(new LambdaQueryWrapper<EvaluationSession>()
                .lt(EvaluationSession::getStartedAt, cutoff)
                .last("LIMIT " + batchSize));
    }
}
