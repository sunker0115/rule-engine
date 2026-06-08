package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecuted;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 消费 ActionExecuted，落 action_execution（uk_idempotency 行级 backstop）。 */
@Component
public class ActionExecutionPersister {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionPersister.class);

    private final ActionExecutionMapper executionMapper;

    public ActionExecutionPersister(ActionExecutionMapper executionMapper) {
        this.executionMapper = executionMapper;
    }

    /**
     * 消费 action 执行完成事件，写一行 action_execution 审计。
     * 写库异常静默吞掉（DuplicateKeyException 为预期内的幂等 backstop），不向上传播。
     *
     * @param e action 执行完成事件
     */
    @EventListener
    public void accept(ActionExecuted e) {
        ActionExecutionEntity entity = new ActionExecutionEntity();
        entity.setEvaluationSessionId(e.sessionId());
        entity.setTenantId(e.tenantId());
        entity.setEventId(e.eventId());
        entity.setActionId(e.actionId());
        entity.setActionType(e.actionType());
        entity.setDecisionCode(e.decisionCode());
        entity.setStatus(e.result().status().name());
        entity.setErrorCode(e.result().errorCode());
        entity.setRetryable(e.result().retryable());
        entity.setRetryCount(0);
        entity.setCompensated(false);
        entity.setExecutedAt(LocalDateTime.now());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            executionMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            // 行级 backstop：缓存漏掉的重复（重启/多实例）在此撞 uk_idempotency，预期内
            log.debug("action_execution 幂等行已存在(uk backstop)，actionId={}, eventId={}",
                    e.actionId(), e.eventId());
        } catch (Exception ex) {
            log.warn("action_execution 写库失败，actionId={}, actionType={}: {}",
                    e.actionId(), e.actionType(), ex.getMessage());
        }
    }
}
