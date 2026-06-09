package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 配置操作审计的集中落库监听器（B 类，单一入口）：消费 {@link OperationAuditedEvent}，
 * 在发布方事务提交前（BEFORE_COMMIT）于同一事务内 INSERT audit_log，满足 D14 同事务红线。
 */
@Component
@RequiredArgsConstructor
public class AuditLogWriter {

    private final AuditLogMapper auditLogMapper;

    /**
     * 落配置操作审计日志，与发布方业务写同事务（BEFORE_COMMIT）。
     *
     * @param event 配置操作审计事件
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOperationAudited(OperationAuditedEvent event) {
        AuditLog log = new AuditLog();
        log.setTenantId(event.tenantId());
        log.setActor(event.actor());
        log.setActorType(event.actorType());
        log.setAction(event.action());
        log.setTargetType(event.targetType());
        log.setTargetId(event.targetId());
        log.setBeforeSnapshot(event.beforeSnapshot());
        log.setAfterSnapshot(event.afterSnapshot());
        log.setOperatedAt(event.operatedAt());
        auditLogMapper.insert(log);
    }
}
