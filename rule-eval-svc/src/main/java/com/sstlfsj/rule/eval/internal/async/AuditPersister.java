package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * 异步批量落审计：消费 {@link AuditRecorded}，单次终态 INSERT evaluation_session + 旁路写 node_trace。
 *
 * <p>best-effort：入队非阻塞，队列满丢弃；批量在虚拟线程消费，不阻塞评估热路径。
 * 审计可丢——溢出/崩溃丢最近未落库审计，{@code uk_tenant_event} 防重复行。
 */
@Component
public class AuditPersister implements InitializingBean, DisposableBean {

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    private final EvaluationSessionMapper sessionMapper;
    private final TraceWriter traceWriter;

    private LinkedBlockingQueue<AuditRecorded> queue;
    private volatile boolean running = false;
    private Thread consumerThread;

    public AuditPersister(int queueCapacity, int batchSize, long flushIntervalMs,
                          EvaluationSessionMapper sessionMapper, TraceWriter traceWriter) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.sessionMapper = sessionMapper;
        this.traceWriter = traceWriter;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuditPersister(EvaluationSessionMapper sessionMapper, TraceWriter traceWriter) {
        this(10000, 500, 200, sessionMapper, traceWriter);
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("audit-persister").start(this::consumeLoop);
    }

    /** 接审计事件，非阻塞入队（队列满丢弃，best-effort）。@EventListener 在发布线程同步入队，开销=一次 offer。 */
    @EventListener
    public void onAudit(AuditRecorded e) {
        queue.offer(e);
    }

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                Thread.sleep(flushIntervalMs);
                flushBatch();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void flushBatch() {
        List<AuditRecorded> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        for (AuditRecorded e : batch) {
            try {
                sessionMapper.insert(toSession(e));
            } catch (DuplicateKeyException ignored) {
                // 幂等：相同 eventId 已落库，丢弃重复（best-effort）
            } catch (RuntimeException ignored) {
                // 审计可丢，不影响主流程
            }
            try {
                traceWriter.write(e.event().tenantId(), String.valueOf(e.sessionId()),
                        e.result().nodeTrace());
            } catch (RuntimeException ignored) {
                // trace 旁路，失败不影响
            }
        }
    }

    private EvaluationSession toSession(AuditRecorded e) {
        RuleEvent ev = e.event();
        EvalResult r = e.result();
        EvaluationSession s = new EvaluationSession();
        s.setId(e.sessionId());
        s.setTenantId(Long.valueOf(ev.tenantId()));
        s.setEventId(ev.eventId());
        s.setSceneCode(ev.sceneCode());
        s.setEventType(ev.eventType());
        s.setSubjectId(ev.subjectId());
        s.setSource(ev.source().name());
        s.setMode(e.mode());
        // BLOCKED（D22 第四态）优先于 MISS：候选被 Pre-Gate 全拦截，blockedBy 记首个阻断 gate
        s.setStatus(r.errorCode() != null ? "ERROR"
                : e.blockedBy() != null ? "BLOCKED"
                : (r.ruleHit() ? "HIT" : "MISS"));
        s.setBlockedBy(e.blockedBy());
        s.setFinalDecision(r.finalDecision() != null ? r.finalDecision().code() : null);
        s.setHitDecisions(r.hitDecisions().isEmpty() ? "[]"
                : r.hitDecisions().stream().map(Decision::code)
                    .collect(Collectors.joining("\",\"", "[\"", "\"]")));
        s.setErrorCode(r.errorCode());
        s.setCandidateRuleCount(e.candidateCount());
        s.setHitRuleCount(r.hitDecisions().size());
        if (ev.occurredAt() != null) {
            s.setOccurredAt(LocalDateTime.ofInstant(ev.occurredAt(), ZoneId.systemDefault()));
        }
        LocalDateTime now = LocalDateTime.now();
        s.setStartedAt(now);
        s.setFinishedAt(now);
        return s;
    }

    @Override
    public void destroy() {
        running = false;
        flushBatch();
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }
}
