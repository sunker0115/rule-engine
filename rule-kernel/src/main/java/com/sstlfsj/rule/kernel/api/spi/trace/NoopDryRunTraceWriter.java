package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import java.util.List;

/** DryRunTraceWriter 空实现，用于测试和禁用场景。 */
public class NoopDryRunTraceWriter implements DryRunTraceWriter {
    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {}
}
