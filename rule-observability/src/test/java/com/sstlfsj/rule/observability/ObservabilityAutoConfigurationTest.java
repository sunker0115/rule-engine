package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.mapper.NodeTraceMapper;
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

    /** 提供 NodeTraceMapper mock Bean，满足 traceWriterDb() 的依赖注入。 */
    @Configuration
    static class MapperMockConfig {
        @Bean
        NodeTraceMapper nodeTraceMapper() {
            return mock(NodeTraceMapper.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    private final ApplicationContextRunner runnerWithMapper = runner
            .withUserConfiguration(MapperMockConfig.class);

    @Test
    void traceWriterDb_registeredByDefault() {
        // 未设置任何属性时，matchIfMissing=true 应注册 TraceWriterDbImpl
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
        // 禁用时不需要 NodeTraceMapper，使用基础 runner
        runner.withPropertyValues("engine.rule.trace.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TraceWriter.class);
                    assertThat(ctx.getBean(TraceWriter.class)).isInstanceOf(NoopTraceWriter.class);
                });
    }
}
