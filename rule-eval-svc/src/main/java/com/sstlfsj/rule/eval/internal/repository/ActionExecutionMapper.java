package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** action_execution 表 Mapper。异步消费侧用 {@link #insertBatch} 多行批量落库（单次往返/单次 fsync）。 */
@Mapper
public interface ActionExecutionMapper extends BaseMapper<ActionExecutionEntity> {

    /**
     * 多行批量 INSERT（id 自增，省略）；撞 uk_idempotency 的重复行经 ON DUPLICATE KEY 空更新跳过
     * （行级 backstop：claim 漏掉的重复在此被吞，非 dup 的真错误仍抛出供消费侧记录）。
     *
     * @param list 待落库的执行记录（非空）
     * @return 影响行数
     */
    @Insert("""
            <script>
            INSERT INTO action_execution
              (evaluation_session_id, tenant_id, event_id, action_id, action_type, decision_code,
               status, error_code, retryable, retry_count, executed_at, compensated, created_at)
            VALUES
            <foreach collection="list" item="e" separator=",">
              (#{e.evaluationSessionId}, #{e.tenantId}, #{e.eventId}, #{e.actionId}, #{e.actionType},
               #{e.decisionCode}, #{e.status}, #{e.errorCode}, #{e.retryable}, #{e.retryCount},
               #{e.executedAt}, #{e.compensated}, #{e.createdAt})
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatch(@Param("list") List<ActionExecutionEntity> list);
}
