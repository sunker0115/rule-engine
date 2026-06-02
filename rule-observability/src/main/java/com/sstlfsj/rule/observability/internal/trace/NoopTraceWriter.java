package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;

import java.util.List;

/** 测试与 SDK 嵌入模式下的空实现，直接丢弃 trace 数据。 */
public class NoopTraceWriter implements TraceWriter {

    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {
        // 空实现，不做任何操作
    }
}
