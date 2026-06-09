package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.event.DomainEvent;
import com.sstlfsj.rule.eval.internal.event.Durability;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/**
 * dry-run 完成事件(best-effort):供异步落 dry_run_session + trace(单次终态)。
 *
 * @param sessionId     请求线程生成的会话 id（snowflake）
 * @param event         触发评估的事件
 * @param ruleVersionId 本次 dry-run 试算的规则版本 id
 * @param result        评估结果
 * @param context       评估上下文（用于 started_at + context_snapshot；可为 null）
 * @param durationMs    评估耗时（毫秒），评估完成时测量并随事件携带，供持久化 eval_duration_ms
 */
public record DryRunRecordedEvent(long sessionId, RuleEvent event, Long ruleVersionId,
                             EvalResult result, EvalContext context, int durationMs) implements DomainEvent {
    @Override
    public Durability durability() { return Durability.BEST_EFFORT; }
}
