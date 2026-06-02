package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void traceWriterDb_registeredByDefault() {
        // 未设置任何属性时，matchIfMissing=true 应注册 TraceWriterDbImpl
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(TraceWriter.class);
            assertThat(ctx.getBean(TraceWriter.class)).isInstanceOf(TraceWriterDbImpl.class);
        });
    }

    @Test
    void traceWriterDb_registeredWhenEnabled() {
        runner.withPropertyValues("engine.rule.trace.enabled=true")
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
}
