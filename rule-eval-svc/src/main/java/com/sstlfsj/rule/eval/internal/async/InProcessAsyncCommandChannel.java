package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ActionCommandChannel} 的本期实现：进程内异步队列 + 虚拟线程消费，best-effort 派发 action。
 *
 * <p>请求线程仅非阻塞入队，派发在后台执行，不阻塞评估、不占 DB 连接。队列满/进程崩溃则丢弃——
 * 本期 ActionHandler 为 stub，副作用可丢；待接入真实「不可丢」handler 时，换 Kafka/AMQP 实现本接口即可，
 * 发布方（评估服务）不动。这是预留的 MQ 缝。
 */
@Component
public class InProcessAsyncCommandChannel
        implements ActionCommandChannel, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(InProcessAsyncCommandChannel.class);

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    private final ActionDispatchService dispatchService;
    /** 队列满丢弃累计计数(best-effort 可丢,但不能无声)。 */
    private final AtomicLong droppedCount = new AtomicLong();

    private LinkedBlockingQueue<DispatchActionsCommand> queue;
    private volatile boolean running = false;
    private Thread consumerThread;

    public InProcessAsyncCommandChannel(int queueCapacity, int batchSize, long flushIntervalMs,
                                         ActionDispatchService dispatchService) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.dispatchService = dispatchService;
    }

    @Autowired
    public InProcessAsyncCommandChannel(ActionDispatchService dispatchService) {
        this(10000, 500, 200, dispatchService);
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("action-delivery").start(this::consumeLoop);
    }

    @Override
    public void deliver(DispatchActionsCommand event) {
        // 非阻塞入队，best-effort：队列满则丢弃，但累计计数 + WARN，不静默
        if (!queue.offer(event)) {
            long dropped = droppedCount.incrementAndGet();
            log.warn("action 派发队列已满(capacity={})，丢弃命令 eventId={}（best-effort，累计丢弃 {} 条）",
                    queueCapacity, event.eventId(), dropped);
        }
    }

    /** 队列满累计丢弃数(供监控/测试观测)。 */
    public long droppedCount() {
        return droppedCount.get();
    }

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                Thread.sleep(flushIntervalMs);
                flushBatch();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void flushBatch() {
        List<DispatchActionsCommand> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        for (DispatchActionsCommand e : batch) {
            try {
                dispatchService.dispatch(e.sessionId(), e.tenantId(), e.eventId(),
                        e.sceneCode(), e.finalDecision());
            } catch (RuntimeException ignored) {
                // best-effort：单条派发失败不影响其余
            }
        }
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
