package com.sstlfsj.rule.eval.internal.session;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 封装 evaluation_session 和 dry_run_session 的同步写入逻辑（D11/D21）。 */
@Component
@RequiredArgsConstructor
public class EvalSessionWriter {

    private static final Logger log = LoggerFactory.getLogger(EvalSessionWriter.class);

    private final EvaluationSessionMapper sessionMapper;
    private final DryRunSessionMapper dryRunMapper;
    private final ObjectMapper objectMapper;

    /**
     * INSERT evaluation_session（status=PENDING）。
     * DuplicateKeyException → 幂等处理，返回已有行 id（D11 下半层）。
     *
     * @param event          触发事件
     * @param candidateCount 候选规则数量
     * @param source         来源标识（PUSH / PULL / REPLAY）
     * @return 写入行的自增 id
     */
    public Long insertPending(RuleEvent event, int candidateCount, String source) {
        EvaluationSession session = buildSession(event, source);
        session.setStatus("PENDING");
        session.setCandidateRuleCount(candidateCount);
        session.setHitRuleCount(0);

        try {
            sessionMapper.insert(session);
            return session.getId();
        } catch (DuplicateKeyException e) {
            // 幂等：相同 eventId 已处理过，查回已有 id
            EvaluationSession existing = sessionMapper.findByTenantAndEvent(
                    Long.valueOf(event.tenantId()), event.eventId());
            if (existing == null) {
                throw new IllegalStateException("幂等查询失败：eventId=" + event.eventId() + " 记录不存在");
            }
            return existing.getId();
        }
    }

    /**
     * INSERT evaluation_session（status=BLOCKED，Pre-Gate 全部拦截路径）。
     *
     * @param event     触发事件
     * @param blockedBy 首个阻断的 Gate 类型
     * @param source    来源标识
     */
    public void insertBlocked(RuleEvent event, String blockedBy, String source) {
        EvaluationSession session = buildSession(event, source);
        session.setStatus("BLOCKED");
        session.setBlockedBy(blockedBy);
        session.setCandidateRuleCount(0);
        session.setHitRuleCount(0);
        session.setFinishedAt(LocalDateTime.now());
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException ignored) {
            // 已有幂等行，不重复写入
        }
    }

    /**
     * UPDATE evaluation_session：将 PENDING 更新为终态（HIT / MISS / ERROR），同步写入 context_snapshot。
     *
     * @param sessionId 待更新的会话 id
     * @param result    评估结果
     * @param ctx       本次评估上下文，用于序列化 metrics 快照；为 null 时 context_snapshot 写 null
     */
    public void updateFinal(Long sessionId, EvalResult result, EvalContext ctx) {
        String status;
        if (result.errorCode() != null) {
            status = "ERROR";
        } else {
            status = result.ruleHit() ? "HIT" : "MISS";
        }

        String finalDecision = result.finalDecision() != null
                ? result.finalDecision().code() : null;
        String hitDecisionsJson = result.hitDecisions().isEmpty() ? "[]"
                : result.hitDecisions().stream()
                    .map(Decision::code)
                    .collect(Collectors.joining("\",\"", "[\"", "\"]"));

        sessionMapper.markFinal(sessionId, status, finalDecision, hitDecisionsJson,
                result.errorCode(), result.hitDecisions().size(), LocalDateTime.now(),
                serializeSnapshot(ctx));
    }

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

    private EvaluationSession buildSession(RuleEvent event, String source) {
        EvaluationSession s = new EvaluationSession();
        s.setTenantId(Long.valueOf(event.tenantId()));
        s.setEventId(event.eventId());
        s.setSceneCode(event.sceneCode());
        s.setEventType(event.eventType());
        s.setSubjectId(event.subjectId());
        s.setSource(source);
        s.setOccurredAt(toLocalDateTime(event.occurredAt()));
        s.setStartedAt(LocalDateTime.now());
        return s;
    }

    private LocalDateTime toLocalDateTime(java.time.Instant instant) {
        if (instant == null) throw new IllegalArgumentException("occurredAt 不得为 null");
        return LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }
}
