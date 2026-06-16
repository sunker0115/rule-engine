package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.alarm.ObservabilityAlarmChecker;
import com.sstlfsj.rule.observability.internal.alarm.ObservabilityAlarmListener;
import com.sstlfsj.rule.observability.internal.alarm.ObservabilityAlarmProperties;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import com.sstlfsj.rule.observability.internal.retention.RetentionProperties;
import com.sstlfsj.rule.observability.internal.retention.TraceRetentionCleaner;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** 自动装配规则可观测性模块（指标 + TraceWriter）。 */
@AutoConfiguration
@EnableConfigurationProperties({TraceWriterProperties.class, RetentionProperties.class, ObservabilityAlarmProperties.class})
public class ObservabilityAutoConfiguration {

    /**
     * 注册 trace 表数据保留清理调度 bean（node_trace）。
     * 可通过 engine.rule.retention.enabled=false 关闭。
     */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.retention.enabled", matchIfMissing = true)
    public TraceRetentionCleaner traceRetentionCleaner(NodeTraceMapper nodeTraceMapper,
                                                       RetentionProperties retentionProperties) {
        return new TraceRetentionCleaner(nodeTraceMapper, retentionProperties);
    }

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
     * 告警阈值检查器：定期读 MeterRegistry，超阈值发 EvalAlarmEvent。
     *
     * @param meterRegistry  Micrometer 注册表
     * @param props          告警阈值配置
     * @param eventPublisher Spring 事件发布器
     * @return ObservabilityAlarmChecker 实例
     */
    @Bean
    public ObservabilityAlarmChecker observabilityAlarmChecker(MeterRegistry meterRegistry,
                                                               ObservabilityAlarmProperties props,
                                                               ApplicationEventPublisher eventPublisher) {
        return new ObservabilityAlarmChecker(meterRegistry, props, eventPublisher);
    }

    /**
     * 告警监听器（v1 打 WARN 日志）；替换此 bean 可接入 Webhook / 钉钉等通道。
     *
     * @return ObservabilityAlarmListener 实例
     */
    @Bean
    public ObservabilityAlarmListener observabilityAlarmListener() {
        return new ObservabilityAlarmListener();
    }
}
