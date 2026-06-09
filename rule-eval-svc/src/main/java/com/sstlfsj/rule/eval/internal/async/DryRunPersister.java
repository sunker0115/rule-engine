package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import com.sstlfsj.rule.eval.internal.domain.SessionStatus;
import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 消费 DryRunRecordedEvent,单次终态 INSERT dry_run_session + dry-run trace。 */
@Component
public class DryRunPersister {

    private static final Logger log = LoggerFactory.getLogger(DryRunPersister.class);

    private final DryRunSessionMapper dryRunMapper;
    private final DryRunTraceWriter traceWriter;
    private final ObjectMapper objectMapper;

    public DryRunPersister(DryRunSessionMapper dryRunMapper, DryRunTraceWriter traceWriter,
                           ObjectMapper objectMapper) {
        this.dryRunMapper = dryRunMapper;
        this.traceWriter = traceWriter;
        this.objectMapper = objectMapper;
    }

    /** 接 dry-run 完成事件,组装终态实体单次 INSERT,再旁路写 trace;落库失败丢弃(best-effort)。 */
    @EventListener
    public void accept(DryRunRecordedEvent e) {
        RuleEvent ev = e.event();
        EvalResult r = e.result();
        DryRunSession s = new DryRunSession();
        s.setId(e.sessionId());
        s.setTenantId(Long.valueOf(ev.tenantId()));
        s.setEventId(ev.eventId());
        s.setSceneCode(ev.sceneCode());
        s.setEventType(ev.eventType());
        s.setSubjectId(ev.subjectId());
        s.setRuleVersionId(e.ruleVersionId());
        s.setStatus(r.errorCode() != null ? SessionStatus.ERROR
                : (r.ruleHit() ? SessionStatus.HIT : SessionStatus.MISS));
        s.setFinalDecision(r.finalDecision() != null ? r.finalDecision().code() : null);
        s.setErrorCode(r.errorCode());
        if (ev.occurredAt() != null) {
            s.setOccurredAt(LocalDateTime.ofInstant(ev.occurredAt(), ZoneId.systemDefault()));
        }
        // started_at 取真实评估起点 context.now()；context 为 null 退回落库时刻；finished = start + 评估耗时
        LocalDateTime start = e.context() != null
                ? LocalDateTime.ofInstant(e.context().now(), ZoneId.systemDefault())
                : LocalDateTime.now();
        s.setStartedAt(start);
        s.setFinishedAt(start.plusNanos(e.durationMs() * 1_000_000L));
        s.setEvalDurationMs(e.durationMs());
        s.setContextSnapshot(ContextSnapshotSerializer.serialize(objectMapper, e.context()));
        try {
            dryRunMapper.insert(s);
        } catch (Exception ex) {
            log.warn("dry_run_session 写库失败, sessionId={}: {}", e.sessionId(), ex.getMessage());
            return;
        }
        traceWriter.write(ev.tenantId(), String.valueOf(e.sessionId()), r.nodeTrace());
    }
}
