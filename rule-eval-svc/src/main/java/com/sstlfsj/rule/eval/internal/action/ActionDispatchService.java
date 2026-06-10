package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecutedEvent;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 规则命中后派发 finalDecision 挂载的 Action（D27），逐条发布
 * {@link com.sstlfsj.rule.eval.internal.async.ActionExecutedEvent} 事件；action_execution 落库由
 * ActionExecutionPersister 异步消费。best-effort fire-and-forget，不重试不补偿。
 */
public class ActionDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ActionDispatchService.class);

    private final Map<String, ActionHandler> handlers;
    private final DomainEventPublisher eventPublisher;

    public ActionDispatchService(Map<String, ActionHandler> handlers,
                                 DomainEventPublisher eventPublisher) {
        this.handlers = handlers;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 派发 finalDecision 挂载的 action（D27）：逐个 action 执行 handler、发 ActionExecutedEvent。
     * 仅 finalDecision 的 action 被派发；handler 缺失则 NO_HANDLER skip。
     *
     * @param sessionId     评估会话 ID
     * @param tenantId      租户 ID
     * @param eventId       业务事件 ID（用于幂等唯一键）
     * @param sceneCode     场景编码
     * @param finalDecision 合成的最终决策（携带 actions）
     */
    public void dispatch(Long sessionId, Long tenantId, String eventId,
                         String sceneCode, Decision finalDecision) {
        if (finalDecision == null) {
            return;
        }
        for (RuleVersionSnapshot.DecisionAction action : finalDecision.actions()) {
            ActionResult result = executeHandler(action, finalDecision);
            // best-effort fire-and-forget：重复防护降级为落库 uk_idempotency（ON DUPLICATE KEY 吞重）
            eventPublisher.publish(new ActionExecutedEvent(
                    sessionId, tenantId, eventId, action.actionId(), action.actionType(),
                    finalDecision.code(), result));
        }
    }

    private ActionResult executeHandler(RuleVersionSnapshot.DecisionAction action, Decision finalDecision) {
        ActionHandler handler = handlers.get(action.actionType());
        if (handler == null) {
            return ActionResult.skipped(action.actionId(), action.actionType(), "NO_HANDLER");
        }
        // params 取自 decision.action（D27：action 的触发+参数归 decision，与 scene 无关）
        Map<String, Object> params = action.params() != null ? action.params() : Map.of();
        ActionContext ctx = new ActionContext(action.actionId(), action.actionType(),
                params, null, null, finalDecision.code());
        return handler.execute(ctx);
    }
}
