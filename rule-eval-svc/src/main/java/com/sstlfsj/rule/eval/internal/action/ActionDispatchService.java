package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 规则命中后同步派发 ActionHandler，结果写 action_execution。
 * v1 handler 均为 stub，同步调用无性能影响；v1.5 接真实 handler 时在此提取异步层。
 */
public class ActionDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ActionDispatchService.class);

    private final Map<String, ActionHandler> handlers;
    private final SceneActionBindingReadMapper bindingMapper;
    private final ActionExecutionMapper executionMapper;
    private final ActionIdempotencyGuard idempotencyGuard;

    public ActionDispatchService(Map<String, ActionHandler> handlers,
                                 SceneActionBindingReadMapper bindingMapper,
                                 ActionExecutionMapper executionMapper,
                                 ActionIdempotencyGuard idempotencyGuard) {
        this.handlers = handlers;
        this.bindingMapper = bindingMapper;
        this.executionMapper = executionMapper;
        this.idempotencyGuard = idempotencyGuard;
    }

    /**
     * 派发本次命中的所有 Decision 对应的 Action，逐条插入 action_execution。
     * insert 异常静默记录 warn 日志，不向上传播（审计失败不影响 EvalResult）。
     *
     * @param sessionId    评估会话 ID
     * @param tenantId     租户 ID
     * @param eventId      业务事件 ID（用于幂等唯一键）
     * @param sceneCode    场景编码
     * @param hitDecisions 本次命中的 Decision 列表
     */
    public void dispatch(Long sessionId, Long tenantId, String eventId,
                         String sceneCode, List<Decision> hitDecisions) {
        List<SceneActionBindingRow> bindings = bindingMapper.findBySceneCode(tenantId, sceneCode);
        if (bindings.isEmpty()) {
            return;
        }

        for (Decision decision : hitDecisions) {
            for (SceneActionBindingRow binding : bindings) {
                String actionId = binding.actionType();   // 确定化：schema uk_scene_action 保证 scene 内 actionType 唯一
                String key = tenantId + ":" + eventId + ":" + decision.code() + ":" + actionId;
                if (!idempotencyGuard.claim(key)) {
                    log.debug("action 幂等跳过 key={}", key);   // TTL 内已派发，跳过执行与落库
                    continue;
                }
                ActionResult result = executeHandler(actionId, binding, decision);
                if (result.status() == ActionResult.ActionStatus.FAILED) {
                    idempotencyGuard.release(key);   // 失败释放，让后续重发能重试
                }
                insertExecution(sessionId, tenantId, eventId, actionId,
                        binding.actionType(), decision.code(), result);
            }
        }
    }

    private ActionResult executeHandler(String actionId, SceneActionBindingRow binding,
                                        Decision decision) {
        ActionHandler handler = handlers.get(binding.actionType());
        if (handler == null) {
            return ActionResult.skipped(actionId, binding.actionType(), "NO_HANDLER");
        }
        ActionContext ctx = new ActionContext(actionId, binding.actionType(),
                Map.of(), null, null, decision.code());
        return handler.execute(ctx);
    }

    private void insertExecution(Long sessionId, Long tenantId, String eventId,
                                 String actionId, String actionType, String decisionCode,
                                 ActionResult result) {
        ActionExecutionEntity entity = new ActionExecutionEntity();
        entity.setEvaluationSessionId(sessionId);
        entity.setTenantId(tenantId);
        entity.setEventId(eventId);
        entity.setActionId(actionId);
        entity.setActionType(actionType);
        entity.setDecisionCode(decisionCode);
        entity.setStatus(result.status().name());
        entity.setErrorCode(result.errorCode());
        entity.setRetryable(result.retryable());
        entity.setRetryCount(0);
        entity.setCompensated(false);
        entity.setExecutedAt(LocalDateTime.now());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            executionMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 行级 backstop：缓存漏掉的重复（重启/多实例）在此撞 uk_idempotency，预期内
            log.debug("action_execution 幂等行已存在(uk backstop)，actionId={}, eventId={}", actionId, eventId);
        } catch (Exception e) {
            log.warn("action_execution 写库失败，actionId={}, actionType={}: {}",
                    actionId, actionType, e.getMessage());
        }
    }
}
