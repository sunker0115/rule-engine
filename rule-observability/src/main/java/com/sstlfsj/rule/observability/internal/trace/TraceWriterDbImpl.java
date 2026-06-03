package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.domain.NodeTraceEntity;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final NodeTraceMapper nodeTraceMapper;

    // 存 (tenantId, sessionId, traces) 三元组
    private record TraceEntry(String tenantId, String sessionId, List<NodeTrace> traces) {}
    private LinkedBlockingQueue<TraceEntry> queue;

    private volatile boolean running = false;
    private Thread consumerThread;

    public TraceWriterDbImpl(int queueCapacity, int batchSize, long flushIntervalMs,
                             NodeTraceMapper nodeTraceMapper) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.nodeTraceMapper = nodeTraceMapper;
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
        List<TraceEntry> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        for (TraceEntry entry : batch) {
            // sessionId / tenantId 来自业务入队时的字符串，转 Long 时容错
            Long sessionId = parseLong(entry.sessionId());
            Long tenantId = parseLong(entry.tenantId());
            List<NodeTraceEntity> entities = new ArrayList<>();
            flattenToList(entry.traces(), sessionId, tenantId, "", 0, entities);
            if (!entities.isEmpty()) {
                nodeTraceMapper.insertBatch(entities);
            }
        }
    }

    /**
     * 递归展开树形 NodeTrace，将所有节点追加到 {@code out} 列表，不执行数据库操作。
     *
     * @param traces      当前层节点列表
     * @param sessionId   评估会话 ID
     * @param tenantId    租户 ID
     * @param pathPrefix  父节点路径前缀（根节点传空串）
     * @param indexOffset 当前层起始下标（通常为 0）
     * @param out         收集结果的输出列表
     */
    private void flattenToList(List<NodeTrace> traces, Long sessionId, Long tenantId,
                                String pathPrefix, int indexOffset, List<NodeTraceEntity> out) {
        for (int i = 0; i < traces.size(); i++) {
            NodeTrace trace = traces.get(i);
            // 根节点 pathPrefix 为空时直接用下标，子节点拼接父路径
            String nodePath = pathPrefix.isEmpty()
                    ? String.valueOf(i + indexOffset)
                    : pathPrefix + "." + (i + indexOffset);

            NodeTraceEntity entity = new NodeTraceEntity();
            entity.setEvaluationSessionId(sessionId);
            entity.setTenantId(tenantId);
            entity.setNodePath(nodePath);
            entity.setNodeType(trace.nodeType());
            entity.setConditionType(trace.conditionType());
            entity.setMetricCode(trace.metricCode());
            entity.setActualValue(trace.actualValue() == null ? null : trace.actualValue().toString());
            entity.setResult(trace.result());
            entity.setErrorCode(trace.errorCode());
            entity.setValueSource(trace.valueSource());
            entity.setEvaluatedAt(LocalDateTime.now());
            out.add(entity);

            // 递归处理子节点
            if (trace.children() != null && !trace.children().isEmpty()) {
                flattenToList(trace.children(), sessionId, tenantId, nodePath, 0, out);
            }
        }
    }

    /** 将字符串安全转换为 Long，非数字时返回 null。 */
    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void destroy() {
        running = false;
        // 停机前先刷剩余数据，避免队列中有未落库的 trace 丢失
        flushBatch();
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }
}
