package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.observability.internal.domain.DryRunNodeTraceEntity;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * DryRunTraceWriter 异步 DB 实现：BlockingQueue + 虚拟线程消费 + 批量写 dry_run_node_trace。
 * 队列满时静默丢弃，不阻塞评估热路径。
 */
public class DryRunTraceWriterDbImpl implements DryRunTraceWriter, InitializingBean, DisposableBean {

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    private final DryRunNodeTraceMapper dryRunNodeTraceMapper;

    // 存 (tenantId, sessionId, traces) 三元组
    private record TraceEntry(String tenantId, String sessionId, List<NodeTrace> traces) {}
    private LinkedBlockingQueue<TraceEntry> queue;

    private volatile boolean running = false;
    private Thread consumerThread;

    public DryRunTraceWriterDbImpl(int queueCapacity, int batchSize, long flushIntervalMs,
                                   DryRunNodeTraceMapper dryRunNodeTraceMapper) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.dryRunNodeTraceMapper = dryRunNodeTraceMapper;
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        // 使用虚拟线程，降低线程开销
        consumerThread = Thread.ofVirtual().name("dry-run-trace-writer").start(this::consumeLoop);
    }

    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {
        // 非阻塞入队；队列满时丢弃，旁路观察通道不影响热路径
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
            List<DryRunNodeTraceEntity> entities = new ArrayList<>();
            flattenToList(entry.traces(), sessionId, tenantId, "", entities);
            if (!entities.isEmpty()) {
                dryRunNodeTraceMapper.insertBatch(entities);
            }
        }
    }

    /**
     * 递归展开树形 NodeTrace 到实体列表，nodePath 按深度优先编号（"0", "0.0", "0.1"...）。
     *
     * @param traces      当前层节点列表
     * @param sessionId   dry-run 会话 ID
     * @param tenantId    租户 ID
     * @param pathPrefix  父节点路径前缀（根节点传空串）
     * @param out         结果收集列表
     */
    private void flattenToList(List<NodeTrace> traces, Long sessionId, Long tenantId,
                                String pathPrefix, List<DryRunNodeTraceEntity> out) {
        for (int i = 0; i < traces.size(); i++) {
            NodeTrace trace = traces.get(i);
            // 根节点 pathPrefix 为空时直接用下标，子节点拼接父路径
            String nodePath = pathPrefix.isEmpty()
                    ? String.valueOf(i)
                    : pathPrefix + "." + i;

            DryRunNodeTraceEntity entity = new DryRunNodeTraceEntity();
            entity.setDryRunSessionId(sessionId);
            entity.setTenantId(tenantId);
            entity.setNodePath(nodePath);
            entity.setNodeType(trace.nodeType());
            entity.setConditionType(trace.conditionType());
            entity.setMetricCode(trace.metricCode());
            entity.setActualValue(trace.actualValue() == null ? null : trace.actualValue().toString());
            entity.setResult(trace.result());
            entity.setErrorCode(trace.errorCode());
            entity.setValueSource(trace.valueSource());
            entity.setRuleVersionId(trace.ruleVersionId());
            entity.setEvaluatedAt(LocalDateTime.now());
            // params 字段暂不填充：NodeTrace 未携带 ConditionNode 原始 params，v1.5 扩展模型时补全
            out.add(entity);

            // 递归处理子节点
            if (trace.children() != null && !trace.children().isEmpty()) {
                flattenToList(trace.children(), sessionId, tenantId, nodePath, out);
            }
        }
    }

    /** 将字符串安全转换为 Long，非数字时返回 null。 */
    private static Long parseLong(String value) {
        if (value == null) return null;
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
