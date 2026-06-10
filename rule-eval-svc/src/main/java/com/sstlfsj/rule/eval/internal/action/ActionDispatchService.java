package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecutedEvent;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 规则命中后同步派发 ActionHandler，逐条发布 {@link com.sstlfsj.rule.eval.internal.async.ActionExecutedEvent}
 * 事件；action_execution 落库由 ActionExecutionPersister 异步消费。
 */
public class ActionDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ActionDispatchService.class);

    private final Map<String, ActionHandler> handlers;
    private final SceneActionBindingIndex bindingIndex;
    private final DomainEventPublisher eventPublisher;

    public ActionDispatchService(Map<String, ActionHandler> handlers,
                                 SceneActionBindingIndex bindingIndex,
                                 DomainEventPublisher eventPublisher) {
        this.handlers = handlers;
        this.bindingIndex = bindingIndex;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 派发本次命中的所有 Decision 对应的 Action，逐条发布 ActionExecutedEvent 事件。
     * 落库由 ActionExecutionPersister 异步消费，dispatch 只负责执行与发布。
     *
     * @param sessionId    评估会话 ID
     * @param tenantId     租户 ID
     * @param eventId      业务事件 ID（用于幂等唯一键）
     * @param sceneCode    场景编码
     * @param hitDecisions 本次命中的 Decision 列表
     */
    public void dispatch(Long sessionId, Long tenantId, String eventId,
                         String sceneCode, List<Decision> hitDecisions) {
        List<SceneActionBindingRow> bindings = bindingIndex.get(tenantId, sceneCode);
        if (bindings.isEmpty()) {
            return;
        }

        for (Decision decision : hitDecisions) {
            for (SceneActionBindingRow binding : bindings) {
                String actionId = binding.actionType();   // 确定化：schema uk_scene_action 保证 scene 内 actionType 唯一
                ActionResult result = executeHandler(actionId, binding, decision);
                // best-effort fire-and-forget：不做进程内幂等占坑,重复防护降级为落库 uk_idempotency（ON DUPLICATE KEY 吞重）
                eventPublisher.publish(new ActionExecutedEvent(
                        sessionId, tenantId, eventId, actionId, binding.actionType(),
                        decision.code(), result));
            }
        }
    }

    private ActionResult executeHandler(String actionId, SceneActionBindingRow binding,
                                        Decision decision) {
        ActionHandler handler = handlers.get(binding.actionType());
        if (handler == null) {
            return ActionResult.skipped(actionId, binding.actionType(), "NO_HANDLER");
        }
        // params 以 scene_action_binding.default_params 为底（04-extension §3.4）；索引已解析为 Map
        Map<String, Object> params = binding.defaultParams() != null ? binding.defaultParams() : Map.of();
        ActionContext ctx = new ActionContext(actionId, binding.actionType(),
                params, null, null, decision.code());
        return handler.execute(ctx);
    }
}
