package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import java.util.List;

/** dry-run 评估链路追踪数据持久化 SPI，写 dry_run_node_trace 表，与主服务 TraceWriter 隔离。 */
public interface DryRunTraceWriter {
    /**
     * 异步持久化 dry-run 节点追踪列表。
     *
     * @param tenantId  租户 ID
     * @param sessionId dry-run 会话 ID（字符串形式）
     * @param traces    本次评估收集的 NodeTrace 树根节点列表
     */
    void write(String tenantId, String sessionId, List<NodeTrace> traces);
}
