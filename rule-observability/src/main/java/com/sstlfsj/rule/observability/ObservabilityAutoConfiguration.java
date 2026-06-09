package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.NoopDryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import com.sstlfsj.rule.observability.internal.trace.DryRunTraceWriterDbImpl;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** 自动装配规则可观测性模块（指标 + TraceWriter + DryRunTraceWriter）。 */
@AutoConfiguration
@EnableConfigurationProperties(TraceWriterProperties.class)
public class ObservabilityAutoConfiguration {

    /**
     * 默认启用异步 DB 批写 TraceWriter（主服务）。
     * 可通过 engine.rule.trace.enabled=false 切换为 Noop 实现。
     */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "true", matchIfMissing = true)
    public TraceWriter traceWriterDb(NodeTraceMapper nodeTraceMapper, ObjectMapper objectMapper,
                                     TraceWriterProperties props) {
        return new TraceWriterDbImpl(props.getQueueCapacity(), props.getBatchSize(),
                props.getFlushIntervalMs(), nodeTraceMapper, objectMapper);
    }

    /** 当 engine.rule.trace.enabled=false 时注册空实现，用于测试或 SDK 嵌入模式。 */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "false")
    public TraceWriter noopTraceWriter() {
        return new NoopTraceWriter();
    }

    /**
     * 默认启用异步 DB 批写 DryRunTraceWriter（dry-run 隔离写 dry_run_node_trace）。
     * 与 TraceWriter 共享 engine.rule.trace.enabled 开关。
     */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "true", matchIfMissing = true)
    public DryRunTraceWriter dryRunTraceWriterDb(DryRunNodeTraceMapper dryRunNodeTraceMapper,
                                                 ObjectMapper objectMapper,
                                                 TraceWriterProperties props) {
        return new DryRunTraceWriterDbImpl(props.getQueueCapacity(), props.getBatchSize(),
                props.getFlushIntervalMs(), dryRunNodeTraceMapper, objectMapper);
    }

    /** 当 engine.rule.trace.enabled=false 时注册空实现，与 noopTraceWriter 对称。 */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "false")
    public DryRunTraceWriter noopDryRunTraceWriter() {
        return new NoopDryRunTraceWriter();
    }
}
