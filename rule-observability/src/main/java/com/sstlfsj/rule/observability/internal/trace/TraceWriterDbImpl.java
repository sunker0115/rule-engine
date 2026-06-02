package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 主服务 TraceWriter 实现：异步 BlockingQueue + 批量落库（D21）。
 * 队列满时直接丢弃，不阻塞评估热路径。
 */
public class TraceWriterDbImpl implements TraceWriter, InitializingBean, DisposableBean {

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;

    // 存 (tenantId, sessionId, traces) 三元组
    private record TraceEntry(String tenantId, String sessionId, List<NodeTrace> traces) {}
    private LinkedBlockingQueue<TraceEntry> queue;

    private volatile boolean running = false;
    private Thread consumerThread;

    public TraceWriterDbImpl(int queueCapacity, int batchSize, long flushIntervalMs) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        // 使用虚拟线程，降低线程开销
        consumerThread = Thread.ofVirtual().name("trace-writer").start(this::consumeLoop);
    }

    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {
        // 非阻塞入队；队列满时丢弃，旁路观察通道不影响热路径（D21）
        queue.offer(new TraceEntry(tenantId, sessionId, traces));
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
        // 骨架：从 queue 取 batchSize 条批量写入 node_trace 表（v2 实现时注入 NodeTraceMapper）
    }

    @Override
    public void destroy() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }
}
