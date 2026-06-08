package com.sstlfsj.rule.eval.internal.session;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 封装 dry_run_session 的同步两阶段写入（D21）。
 * <p>生产 evaluation_session 落库已改异步事件化（单次终态 INSERT，见 AuditPersister），不再走本类；
 * dry-run 为低频 admin/测试路径，保留两阶段同步写。
 */
@Component
@RequiredArgsConstructor
public class EvalSessionWriter {

    private static final Logger log = LoggerFactory.getLogger(EvalSessionWriter.class);

    private final DryRunSessionMapper dryRunMapper;
    private final ObjectMapper objectMapper;

    /**
     * INSERT dry_run_session（status=PENDING）。
     *
     * @param event         触发事件
     * @param ruleVersionId 本次 dry-run 测试的规则版本 ID
     * @return 写入行的自增 id
     */
    public Long insertDryRunPending(RuleEvent event, Long ruleVersionId) {
        DryRunSession session = new DryRunSession();
        session.setTenantId(Long.valueOf(event.tenantId()));
        session.setEventId(event.eventId());
        session.setSceneCode(event.sceneCode());
        session.setEventType(event.eventType());
        session.setSubjectId(event.subjectId());
        session.setRuleVersionId(ruleVersionId);
        session.setStatus("PENDING");
        session.setOccurredAt(toLocalDateTime(event.occurredAt()));
        session.setStartedAt(LocalDateTime.now());

        dryRunMapper.insert(session);
        return session.getId();
    }

    /**
     * UPDATE dry_run_session 为终态（HIT / MISS / ERROR），同步写入 context_snapshot。
     *
     * @param sessionId 待更新的 dry-run 会话 id
     * @param result    评估结果
     * @param ctx       本次 dry-run 评估上下文；为 null 时 context_snapshot 写 null
     */
    public void updateDryRunFinal(Long sessionId, EvalResult result, EvalContext ctx) {
        String status = result.ruleHit() ? "HIT" : "MISS";
        if (result.errorCode() != null) status = "ERROR";

        dryRunMapper.markFinal(sessionId, status, result.errorCode(),
                result.finalDecision() != null ? result.finalDecision().code() : null,
                LocalDateTime.now(), serializeSnapshot(ctx));
    }

    /**
     * 将 EvalContext 序列化为 {@code {"metrics": {metricCode: rawValue}, "evalNow": "<ISO>"}} JSON；
     * ctx 为 null 或序列化失败时返回 null。
     */
    private String serializeSnapshot(EvalContext ctx) {
        if (ctx == null) return null;
        Map<String, Object> metrics = ctx.metrics().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().value() != null ? e.getValue().value() : "null"));
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("metrics", metrics);
        snapshot.put("evalNow", ctx.now() != null ? ctx.now().toString() : null);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException e) {
            log.warn("context_snapshot 序列化失败，写 null", e);
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(java.time.Instant instant) {
        if (instant == null) throw new IllegalArgumentException("occurredAt 不得为 null");
        return LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }
}
