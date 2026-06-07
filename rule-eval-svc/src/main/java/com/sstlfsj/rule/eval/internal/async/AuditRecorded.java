package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/**
 * 审计领域事件（内存 best-effort）：一次评估完成的事实，供异步持久化 evaluation_session。
 *
 * @param sessionId      请求线程生成的会话 id（snowflake）
 * @param event          触发评估的事件
 * @param mode           评估模式（PUSH / PULL）
 * @param candidateCount 候选规则数
 * @param result         评估结果
 * @param context        评估上下文（用于 context_snapshot；可为 null）
 */
public record AuditRecorded(long sessionId, RuleEvent event, String mode,
                            int candidateCount, EvalResult result, EvalContext context) {}
