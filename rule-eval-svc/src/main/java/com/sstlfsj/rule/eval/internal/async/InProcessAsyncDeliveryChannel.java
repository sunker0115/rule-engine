package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * {@link ActionDeliveryChannel} 的本期实现：进程内异步队列 + 虚拟线程消费，best-effort 派发 action。
 *
 * <p>请求线程仅非阻塞入队，派发在后台执行，不阻塞评估、不占 DB 连接。队列满/进程崩溃则丢弃——
 * 本期 ActionHandler 为 stub，副作用可丢；待接入真实「不可丢」handler 时，换 Kafka/AMQP 实现本接口即可，
 * 发布方（评估服务）不动。这是预留的 MQ 缝。
 */
@Component
public class InProcessAsyncDeliveryChannel
        implements ActionDeliveryChannel, InitializingBean, DisposableBean {

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    private final ActionDispatchService dispatchService;

    private LinkedBlockingQueue<ActionRequested> queue;
    private volatile boolean running = false;
    private Thread consumerThread;

    public InProcessAsyncDeliveryChannel(int queueCapacity, int batchSize, long flushIntervalMs,
                                         ActionDispatchService dispatchService) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.dispatchService = dispatchService;
    }

    @Autowired
    public InProcessAsyncDeliveryChannel(ActionDispatchService dispatchService) {
        this(10000, 500, 200, dispatchService);
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("action-delivery").start(this::consumeLoop);
    }

    @Override
    public void deliver(ActionRequested event) {
        queue.offer(event);   // 非阻塞入队，best-effort
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
        List<ActionRequested> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        for (ActionRequested e : batch) {
            try {
                dispatchService.dispatch(e.sessionId(), e.tenantId(), e.eventId(),
                        e.sceneCode(), e.hitDecisions());
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
