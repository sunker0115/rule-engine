package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.domain.SessionStatus;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 异步批量落审计：消费 {@link AuditRecordedEvent}，单次终态 INSERT evaluation_session + 旁路写 node_trace。
 *
 * <p>best-effort：入队非阻塞，队列满丢弃；批量在虚拟线程消费，不阻塞评估热路径。
 * 审计可丢——溢出/崩溃丢最近未落库审计，{@code uk_tenant_event} 防重复行。
 */
@Slf4j
@Component
// native：HitDecisionView 经 Jackson 序列化 hit_decisions，需注册其 record 组件反射(否则 native 下 writeValueAsString 抛 UnsupportedFeatureError)
@RegisterReflectionForBinding(AuditPersister.HitDecisionView.class)
public class AuditPersister implements InitializingBean, DisposableBean {

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    private final EvaluationSessionMapper sessionMapper;
    private final TraceWriter traceWriter;
    private final ObjectMapper objectMapper;
    private final boolean captureContextSnapshot;

    private LinkedBlockingQueue<AuditRecordedEvent> queue;
    private volatile boolean running = false;
    private Thread consumerThread;

    public AuditPersister(int queueCapacity, int batchSize, long flushIntervalMs,
                          EvaluationSessionMapper sessionMapper, TraceWriter traceWriter,
                          ObjectMapper objectMapper, boolean captureContextSnapshot) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.sessionMapper = sessionMapper;
        this.traceWriter = traceWriter;
        this.objectMapper = objectMapper;
        this.captureContextSnapshot = captureContextSnapshot;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuditPersister(EvaluationSessionMapper sessionMapper, TraceWriter traceWriter,
                          ObjectMapper objectMapper, AuditProperties auditProperties) {
        this(10000, 500, 200, sessionMapper, traceWriter, objectMapper,
                auditProperties.getContextSnapshot().isEnabled());
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("audit-persister").start(this::consumeLoop);
    }

    /** 接审计事件，非阻塞入队（队列满丢弃，best-effort）。@EventListener 在发布线程同步入队，开销=一次 offer。 */
    @EventListener
    public void onAudit(AuditRecordedEvent e) {
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
        List<AuditRecordedEvent> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) return;
        try {
            // 多行批量 INSERT 终态 session（uk 重复 eventId 经 ON DUPLICATE KEY 空更新跳过），单次往返/单次 fsync
            sessionMapper.insertBatch(batch.stream().map(this::toSession).toList());
        } catch (RuntimeException ex) {
            // 审计可丢，整批写库失败不影响主流程；但打 warn 便于区分真故障与正常背压丢弃
            log.warn("审计 session 批量落库失败(best-effort 丢弃 {} 条)", batch.size(), ex);
        }
        for (AuditRecordedEvent e : batch) {
            try {
                traceWriter.write(e.event().tenantId(), String.valueOf(e.sessionId()),
                        e.result().nodeTrace());
            } catch (RuntimeException ex) {
                log.warn("node_trace 旁路写失败 sessionId={}", e.sessionId(), ex);
            }
        }
    }

    private EvaluationSession toSession(AuditRecordedEvent e) {
        RuleEvent ev = e.event();
        EvalResult r = e.result();
        EvaluationSession s = new EvaluationSession();
        s.setId(e.sessionId());
        s.setTenantId(Long.valueOf(ev.tenantId()));
        s.setEventId(ev.eventId());
        s.setSceneCode(ev.sceneCode());
        s.setEventType(ev.eventType());
        s.setSubjectId(ev.subjectId());
        s.setSource(ev.source());
        s.setMode(e.mode());
        // BLOCKED（D22 第四态）优先于 MISS：候选被 Pre-Gate 全拦截，blockedBy 记首个阻断 gate
        s.setStatus(r.errorCode() != null ? SessionStatus.ERROR
                : e.blockedBy() != null ? SessionStatus.BLOCKED
                : (r.ruleHit() ? SessionStatus.HIT : SessionStatus.MISS));
        s.setBlockedBy(e.blockedBy());
        s.setFinalDecision(r.finalDecision() != null ? r.finalDecision().code() : null);
        s.setErrorCode(r.errorCode());
        s.setCandidateRuleCount(e.candidateCount());
        s.setHitRuleCount(r.hitDecisions().size());
        s.setScore(r.score());   // SCORECARD 累计分；其他 kind 为 null
        s.setCategory(r.finalDecision() != null ? r.finalDecision().category() : null);
        // best-effort 序列化：单条 hit_decisions 序列化失败降级为 null，不抛进 .map() 流导致整批落库丢失
        s.setHitDecisions(serializeJson(
                r.hitDecisions().stream()
                        .map(d -> new HitDecisionView(d.code(), d.category(), d.fromRuleVersionId()))
                        .toList()));
        if (ev.occurredAt() != null) {
            s.setOccurredAt(LocalDateTime.ofInstant(ev.occurredAt(), ZoneId.systemDefault()));
        }
        // started_at 取真实评估起点 context.now()（落库时刻晚于评估，不能代表评估窗口）；context 为 null 退回落库时刻
        LocalDateTime start = e.context() != null
                ? LocalDateTime.ofInstant(e.context().now(), ZoneId.systemDefault())
                : LocalDateTime.now();
        s.setStartedAt(start);
        s.setFinishedAt(start.plusNanos(e.durationMs() * 1_000_000L));
        s.setEvalDurationMs(e.durationMs());
        // 开关开启才回填重放三件套(payload + 候选版本 id + context_snapshot;默认见 AuditProperties)；
        // context 为 null 时 serializer 返回 null，序列化失败 best-effort 写 null
        if (captureContextSnapshot) {
            s.setContextSnapshot(ContextSnapshotSerializer.serialize(objectMapper, e.context()));
            s.setPayload(serializeJson(ev.payload()));
            s.setCandidateRuleVersionIds(serializeJson(e.candidateVersionIds()));
        }
        return s;
    }

    /** best-effort 序列化为 JSON 文本；null 入参或序列化失败返回 null。 */
    private String serializeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** hit_decisions JSON 元素：每条命中决策的码 + 分类 + 来源规则版本。 */
    /** hit_decisions JSON 元素：码 + 分类 + 来源规则版本。包级可见以便 native 反射 hints 引用。 */
    record HitDecisionView(String code, String category, Long ruleVersionId) {}

    @Override
    public void destroy() {
        running = false;
        // 停机排空整个队列（不止一批），避免积压 > batchSize 的审计被丢
        while (queue != null && !queue.isEmpty()) {
            flushBatch();
        }
        if (consumerThread != null) {
            consumerThread.interrupt();
            try {
                consumerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
