package com.sstlfsj.rule.observability.internal.trace;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 TraceWriterProperties 绑定 engine.rule.trace.* 调优字段，默认 10000/500/200。 */
class TraceWriterPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(TraceWriterProperties.class)
    static class Config {}

    @Test
    void defaults_matchHardcodedValues() {
        runner.run(ctx -> {
            TraceWriterProperties props = ctx.getBean(TraceWriterProperties.class);
            assertThat(props.getQueueCapacity()).isEqualTo(10000);
            assertThat(props.getBatchSize()).isEqualTo(500);
            assertThat(props.getFlushIntervalMs()).isEqualTo(200L);
        });
    }

    @Test
    void binds_underEngineRuleTracePrefix() {
        runner.withPropertyValues(
                        "engine.rule.trace.queue-capacity=20000",
                        "engine.rule.trace.batch-size=1000",
                        "engine.rule.trace.flush-interval-ms=500")
                .run(ctx -> {
                    TraceWriterProperties props = ctx.getBean(TraceWriterProperties.class);
                    assertThat(props.getQueueCapacity()).isEqualTo(20000);
                    assertThat(props.getBatchSize()).isEqualTo(1000);
                    assertThat(props.getFlushIntervalMs()).isEqualTo(500L);
                });
    }
}
