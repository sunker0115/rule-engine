package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.event.DomainEvent;
import com.sstlfsj.rule.eval.internal.event.Durability;
import com.sstlfsj.rule.kernel.api.model.ActionResult;

/** action 执行完成事件(best-effort)：供异步落 action_execution；队列满/重启可丢,不重试,可靠投递未来接 MQ。 */
public record ActionExecutedEvent(long sessionId, long tenantId, String eventId, String actionId,
                             String actionType, String decisionCode, ActionResult result)
        implements DomainEvent {
    @Override
    public Durability durability() {
        return Durability.BEST_EFFORT;
    }
}
