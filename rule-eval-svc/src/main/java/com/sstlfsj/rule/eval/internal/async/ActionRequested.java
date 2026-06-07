package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.Decision;

import java.io.Serializable;
import java.util.List;

/**
 * action 派发事件（持久 outbox，at-least-once）。实现 {@link Serializable} 供 event_publication 序列化。
 *
 * @param sessionId    评估会话 id
 * @param tenantId     租户 id
 * @param eventId      业务事件 id（幂等键，供 handler 去重）
 * @param sceneCode    场景编码
 * @param hitDecisions 本次命中的决策列表
 */
public record ActionRequested(long sessionId, long tenantId, String eventId,
                              String sceneCode, List<Decision> hitDecisions) implements Serializable {}
