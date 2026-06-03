package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import java.util.List;

/** 持久化规则评估链路追踪数据，用于调试与审计的 SPI 接口。 */
public interface TraceWriter {
    void write(String tenantId, String sessionId, List<NodeTrace> traces);
}
