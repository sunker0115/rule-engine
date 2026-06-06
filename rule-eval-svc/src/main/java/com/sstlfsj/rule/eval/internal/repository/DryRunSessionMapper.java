package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/** dry_run_session 表 CRUD Mapper。 */
@Mapper
public interface DryRunSessionMapper extends BaseMapper<DryRunSession> {

    /**
     * 将 dry-run 会话按 id 更新为终态（HIT / MISS / ERROR），显式 set 各字段（含可为 null 的列）。
     * 用 LambdaUpdateWrapper 而非 updateById：errorCode / finalDecision 等需写入 null 值。
     */
    default void markFinal(Long id, String status, String errorCode, String finalDecision,
                           LocalDateTime finishedAt, String contextSnapshot) {
        update(null, new LambdaUpdateWrapper<DryRunSession>()
                .eq(DryRunSession::getId, id)
                .set(DryRunSession::getStatus, status)
                .set(DryRunSession::getErrorCode, errorCode)
                .set(DryRunSession::getFinalDecision, finalDecision)
                .set(DryRunSession::getFinishedAt, finishedAt)
                .set(DryRunSession::getContextSnapshot, contextSnapshot));
    }
}
