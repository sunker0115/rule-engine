package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.NoopDryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import com.sstlfsj.rule.observability.internal.trace.DryRunTraceWriterDbImpl;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        runner.withPropertyValues("engine.rule.trace.enabled=false")
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
        runner.withPropertyValues("engine.rule.trace.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DryRunTraceWriter.class);
                    assertThat(ctx.getBean(DryRunTraceWriter.class)).isInstanceOf(NoopDryRunTraceWriter.class);
                });
    }
}
