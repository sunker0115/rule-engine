package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.NoopDryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import com.sstlfsj.rule.observability.internal.retention.RetentionProperties;
import com.sstlfsj.rule.observability.internal.retention.TraceRetentionCleaner;
import com.sstlfsj.rule.observability.internal.trace.DryRunTraceWriterDbImpl;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ObservabilityAutoConfigurationTest {

    @Configuration
    static class MapperMockConfig {
        @Bean
        NodeTraceMapper nodeTraceMapper() {
            return mock(NodeTraceMapper.class);
        }

        @Bean
        DryRunNodeTraceMapper dryRunNodeTraceMapper() {
            return mock(DryRunNodeTraceMapper.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    private final ApplicationContextRunner runnerWithMapper = runner
            .withUserConfiguration(MapperMockConfig.class);

    @Test
    void traceWriterDb_registeredByDefault() {
        runnerWithMapper.run(ctx -> {
            assertThat(ctx).hasSingleBean(TraceWriter.class);
            assertThat(ctx.getBean(TraceWriter.class)).isInstanceOf(TraceWriterDbImpl.class);
        });
    }

    @Test
    void traceWriterDb_registeredWhenEnabled() {
        runnerWithMapper.withPropertyValues("engine.rule.trace.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TraceWriter.class);
                    assertThat(ctx.getBean(TraceWriter.class)).isInstanceOf(TraceWriterDbImpl.class);
                });
    }

    @Test
    void noopTraceWriter_registeredWhenDisabled() {
        // 关 retention 避免清理 bean 在无 mapper 的精简 runner 中尝试注入
        runner.withPropertyValues("engine.rule.trace.enabled=false", "engine.rule.retention.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TraceWriter.class);
                    assertThat(ctx.getBean(TraceWriter.class)).isInstanceOf(NoopTraceWriter.class);
                });
    }

    @Test
    void dryRunTraceWriterDb_registeredByDefault() {
        runnerWithMapper.run(ctx -> {
            assertThat(ctx).hasSingleBean(DryRunTraceWriter.class);
            assertThat(ctx.getBean(DryRunTraceWriter.class)).isInstanceOf(DryRunTraceWriterDbImpl.class);
        });
    }

    @Test
    void dryRunTraceWriterDb_registeredWhenEnabled() {
        runnerWithMapper.withPropertyValues("engine.rule.trace.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DryRunTraceWriter.class);
                    assertThat(ctx.getBean(DryRunTraceWriter.class)).isInstanceOf(DryRunTraceWriterDbImpl.class);
                });
    }

    @Test
    void noopDryRunTraceWriter_registeredWhenDisabled() {
        // 关 retention 避免清理 bean 在无 mapper 的精简 runner 中尝试注入
        runner.withPropertyValues("engine.rule.trace.enabled=false", "engine.rule.retention.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DryRunTraceWriter.class);
                    assertThat(ctx.getBean(DryRunTraceWriter.class)).isInstanceOf(NoopDryRunTraceWriter.class);
                });
    }

    @Test
    void traceWriterProperties_registeredWithHardcodedDefaults() {
        runnerWithMapper.run(ctx -> {
            TraceWriterProperties props = ctx.getBean(TraceWriterProperties.class);
            assertThat(props.getQueueCapacity()).isEqualTo(10000);
            assertThat(props.getBatchSize()).isEqualTo(500);
            assertThat(props.getFlushIntervalMs()).isEqualTo(200L);
        });
    }

    @Test
    void traceRetentionCleaner_registeredByDefault() {
        runnerWithMapper.run(ctx -> assertThat(ctx).hasSingleBean(TraceRetentionCleaner.class));
    }

    @Test
    void traceRetentionCleaner_notRegisteredWhenDisabled() {
        runnerWithMapper.withPropertyValues("engine.rule.retention.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TraceRetentionCleaner.class));
    }

    @Test
    void retentionProperties_registeredWithHardcodedDefaults() {
        runnerWithMapper.run(ctx -> {
            RetentionProperties props = ctx.getBean(RetentionProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getNodeTraceDays()).isEqualTo(30);
            assertThat(props.getDryRunSessionDays()).isEqualTo(7);
            assertThat(props.getBatchSize()).isEqualTo(1000);
        });
    }
}
