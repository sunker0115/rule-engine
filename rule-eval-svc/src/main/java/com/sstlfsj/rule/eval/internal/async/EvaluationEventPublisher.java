package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 评估出站事件发布点：审计始终发（内存 best-effort），action 在命中且有绑定时发（持久 outbox）。
 *
 * <p>两类事件分离两种持久性：审计可丢走 Spring 应用事件（{@link ApplicationEventPublisher}）；
 * action 不可丢走 {@link ActionDeliveryChannel}（at-least-once）。
 */
@Component
public class EvaluationEventPublisher {

    private final ApplicationEventPublisher publisher;
    private final ActionDeliveryChannel actionDelivery;

    public EvaluationEventPublisher(ApplicationEventPublisher publisher,
                                    ActionDeliveryChannel actionDelivery) {
        this.publisher = publisher;
        this.actionDelivery = actionDelivery;
    }

    /**
     * 发布审计事件（内存 best-effort）。
     *
     * @param sessionId      会话 id
     * @param event          触发事件
     * @param mode           评估模式（PUSH / PULL）
     * @param candidateCount 候选规则数
     * @param result         评估结果
     * @param context        评估上下文（可为 null）
     */
    public void publishAudit(long sessionId, RuleEvent event, String mode,
                             int candidateCount, EvalResult result, EvalContext context) {
        publisher.publishEvent(new AuditRecorded(sessionId, event, mode, candidateCount, result, context));
    }

    /**
     * 命中且有 action 绑定时发持久 action 事件（at-least-once，不丢）。
     *
     * @param sessionId    会话 id
     * @param tenantId     租户 id
     * @param eventId      业务事件 id（幂等键）
     * @param sceneCode    场景编码
     * @param hitDecisions 命中决策列表
     */
    public void publishActions(long sessionId, long tenantId, String eventId, String sceneCode,
                               List<Decision> hitDecisions) {
        actionDelivery.deliver(new ActionRequested(sessionId, tenantId, eventId, sceneCode, hitDecisions));
    }
}
