package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.domain.EvalMode;
import com.sstlfsj.rule.eval.internal.event.DomainEvent;
import com.sstlfsj.rule.eval.internal.event.Durability;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

import java.util.List;

/**
 * 审计领域事件（内存 best-effort）：一次评估完成的事实，供异步持久化 evaluation_session。
 *
 * @param sessionId           请求线程生成的会话 id（snowflake）
 * @param event               触发评估的事件
 * @param mode                评估模式（PUSH / PULL）
 * @param candidateCount      候选规则数
 * @param result              评估结果
 * @param context             评估上下文（用于 context_snapshot；可为 null）
 * @param blockedBy           Pre-Gate 拦截原因（首个阻断 gateType）；非 null 时落 status=BLOCKED，否则 null
 * @param durationMs          评估耗时（毫秒），评估完成时测量并随事件携带，供持久化 eval_duration_ms
 * @param candidateVersionIds 当时候选规则版本 id 列表（忠实重放用，落 candidate_rule_version_ids）
 */
public record AuditRecordedEvent(long sessionId, RuleEvent event, EvalMode mode,
                            int candidateCount, EvalResult result, EvalContext context,
                            String blockedBy, int durationMs,
                            List<Long> candidateVersionIds) implements DomainEvent {
    @Override
    public Durability durability() { return Durability.BEST_EFFORT; }
}
