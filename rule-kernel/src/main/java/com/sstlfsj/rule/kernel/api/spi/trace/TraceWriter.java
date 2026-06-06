package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import java.util.List;

/** 持久化规则评估链路追踪数据，用于调试与审计的 SPI 接口。 */
public interface TraceWriter {
    /**
     * 异步持久化规则评估节点追踪列表。
     *
     * @param tenantId  租户 ID
     * @param sessionId 评估会话 ID（字符串形式）
     * @param traces    本次评估收集的 NodeTrace 树根节点列表
     */
    void write(String tenantId, String sessionId, List<NodeTrace> traces);
}
