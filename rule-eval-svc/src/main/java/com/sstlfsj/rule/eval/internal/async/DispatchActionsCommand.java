package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.Decision;

import java.io.Serializable;

/**
 * action 派发命令：触发「去执行这次命中 finalDecision 的 action」。本期经 {@link ActionCommandChannel} 进程内
 * 异步投递（best-effort）；实现 {@link Serializable} 以便将来换 MQ 投递时序列化。
 *
 * @param sessionId     评估会话 id
 * @param tenantId      租户 id
 * @param eventId       业务事件 id（幂等键，供 handler 去重）
 * @param sceneCode     场景编码
 * @param finalDecision 本次合成的最终决策（携带 actions），仅它的 action 被派发（D27）
 */
public record DispatchActionsCommand(long sessionId, long tenantId, String eventId,
                                     String sceneCode, Decision finalDecision) implements Serializable {}
