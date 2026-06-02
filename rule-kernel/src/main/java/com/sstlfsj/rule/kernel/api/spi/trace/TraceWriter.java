package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import java.util.List;

/** Persists rule evaluation traces for debugging and auditing. */
public interface TraceWriter {
    void write(String tenantId, String sessionId, List<NodeTrace> traces);
}
