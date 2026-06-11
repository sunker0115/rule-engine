package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecutedEvent;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 异步批量落 action_execution：消费 {@link ActionExecutedEvent}，虚拟线程批量 INSERT（uk_idempotency 行级 backstop）。
 *
 * <p>best-effort：入队非阻塞，队列满丢弃；批量在虚拟线程消费，不阻塞 action 派发线程。
 * 与 {@link com.sstlfsj.rule.eval.internal.async.AuditPersister} 同构——把 insert 解耦出单条
 * action-delivery 消费线程，避免同步内联写库成为 action keep-up 地板。
 */
@Component
public class ActionExecutionPersister implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionPersister.class);

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    private final ActionExecutionMapper executionMapper;

    private LinkedBlockingQueue<ActionExecutedEvent> queue;
    private volatile boolean running = false;
    private Thread consumerThread;

    public ActionExecutionPersister(int queueCapacity, int batchSize, long flushIntervalMs,
                                    ActionExecutionMapper executionMapper) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.executionMapper = executionMapper;
    }

    @Autowired
    public ActionExecutionPersister(ActionExecutionMapper executionMapper) {
        this(10000, 500, 200, executionMapper);
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("action-execution-persister").start(this::consumeLoop);
    }

    /** 接 action 执行完成事件，非阻塞入队（队列满丢弃，best-effort）。@EventListener 在发布线程同步入队，开销=一次 offer。 */
    @EventListener
    public void onActionExecuted(ActionExecutedEvent e) {
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
        List<ActionExecutedEvent> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) return;
        try {
            // 多行批量 INSERT（uk 重复经 ON DUPLICATE KEY 空更新跳过），单次往返/单次 fsync
            executionMapper.insertBatch(batch.stream().map(this::toEntity).toList());
        } catch (RuntimeException ex) {
            // best-effort：整批写库失败丢弃，不影响后续批次与消费线程
            log.warn("action_execution 批量写库失败，丢弃 {} 行: {}", batch.size(), ex.getMessage());
        }
    }

    private ActionExecutionEntity toEntity(ActionExecutedEvent e) {
        ActionExecutionEntity entity = new ActionExecutionEntity();
        entity.setEvaluationSessionId(e.sessionId());
        entity.setTenantId(e.tenantId());
        entity.setEventId(e.eventId());
        entity.setActionId(e.actionId());
        entity.setActionType(e.actionType());
        entity.setDecisionCode(e.decisionCode());
        entity.setStatus(e.result().status());
        entity.setErrorCode(e.result().errorCode());
        LocalDateTime now = LocalDateTime.now();
        entity.setExecutedAt(now);
        entity.setCreatedAt(now);
        return entity;
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
