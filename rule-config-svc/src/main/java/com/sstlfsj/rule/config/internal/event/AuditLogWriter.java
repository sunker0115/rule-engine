package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 配置操作审计的集中落库监听器（B 类，单一入口）：消费 {@link OperationAuditedEvent}，
 * 在发布方事务提交前（BEFORE_COMMIT）于同一事务内 INSERT audit_log，满足 D14 同事务红线。
 *
 * <p>typed {@link AuditSnapshot} 在此处统一序列化为 audit_log 的 JSON 列，
 * 把序列化逻辑从各发布点下沉到单一落库处。
 */
@Component
@RequiredArgsConstructor
// native：AuditSnapshot 各 record 经 Jackson 序列化为 before/after_snapshot，需注册反射(否则 native 下 writeValueAsString 抛 UnsupportedFeatureError)
@RegisterReflectionForBinding({
        DraftCreatedSnapshot.class,
        RulePublishedSnapshot.class,
        RuleStatusSnapshot.class,
        RuleImportedSnapshot.class,
        MetricChangedSnapshot.class,
        ConnectorChangedSnapshot.class
})
public class AuditLogWriter {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

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
        log.setBeforeSnapshot(serialize(event.beforeSnapshot()));
        log.setAfterSnapshot(serialize(event.afterSnapshot()));
        log.setOperatedAt(event.operatedAt());
        // traceId 取自当前请求线程 MDC（OTel 注入，与 ApiResponse 同源）；BEFORE_COMMIT 同步执行，线程内有值
        log.setTraceId(MDC.get("traceId"));
        auditLogMapper.insert(log);
    }

    /**
     * 将 typed 快照序列化为 JSON String 落库；null 快照落 null。
     *
     * <p>审计走 D14 同事务红线，序列化失败不可静默丢——抛 {@link IllegalStateException} 让事务回滚。
     *
     * @param snapshot typed 快照，可空
     * @return JSON 字符串；snapshot 为 null 时返回 null
     */
    private String serialize(AuditSnapshot snapshot) {
        if (snapshot == null) return null;
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException ex) {
            throw new IllegalStateException("审计快照序列化失败: " + snapshot.getClass().getSimpleName(), ex);
        }
    }
}
