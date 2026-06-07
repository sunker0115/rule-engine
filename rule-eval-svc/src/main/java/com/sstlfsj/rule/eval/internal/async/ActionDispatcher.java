package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 消费持久 {@link ActionRequested} 事件，异步派发 action（at-least-once）。
 *
 * <p>{@code @ApplicationModuleListener} 在发布事务提交后异步触发，崩溃未完成项重启重投；
 * 重投会重复调用——幂等责任在 ActionHandler（按 eventId/actionId 去重，不产生重复副作用）。
 */
@Component
public class ActionDispatcher {

    private final ActionDispatchService dispatchService;

    public ActionDispatcher(ActionDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @ApplicationModuleListener
    public void on(ActionRequested e) {
        dispatchService.dispatch(e.sessionId(), e.tenantId(), e.eventId(),
                e.sceneCode(), e.hitDecisions());
    }
}
