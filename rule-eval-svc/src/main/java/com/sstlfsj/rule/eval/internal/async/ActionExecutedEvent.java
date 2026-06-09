package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.event.DomainEvent;
import com.sstlfsj.rule.eval.internal.event.Durability;
import com.sstlfsj.rule.kernel.api.model.ActionResult;

/** action 执行完成事件(at-least-once)：供异步落 action_execution。 */
public record ActionExecutedEvent(long sessionId, long tenantId, String eventId, String actionId,
                             String actionType, String decisionCode, ActionResult result)
        implements DomainEvent {
    @Override
    public Durability durability() {
        return Durability.AT_LEAST_ONCE;
    }
}
