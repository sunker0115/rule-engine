package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.mapper.NodeTraceMapper;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** 自动装配规则可观测性模块（指标 + TraceWriter）。 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    /**
     * 默认启用异步 DB 批写 TraceWriter。
     * 可通过 engine.rule.trace.enabled=false 切换为 Noop 实现。
     */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "true", matchIfMissing = true)
    public TraceWriter traceWriterDb(NodeTraceMapper nodeTraceMapper) {
        return new TraceWriterDbImpl(10000, 500, 200, nodeTraceMapper);
    }

    /** 当 engine.rule.trace.enabled=false 时注册空实现，用于测试或 SDK 嵌入模式。 */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "false")
    public TraceWriter noopTraceWriter() {
        return new NoopTraceWriter();
    }
}
