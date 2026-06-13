package com.sstlfsj.rule.observability.internal.alarm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityAlarmPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(ObservabilityAlarmProperties.class)
    static class Config {}

    @Test
    void defaults() {
        runner.run(ctx -> {
            ObservabilityAlarmProperties p = ctx.getBean(ObservabilityAlarmProperties.class);
            assertThat(p.getEvalErrorRateThreshold()).isEqualTo(0.05);
            assertThat(p.getTraceQueueFullThreshold()).isEqualTo(0.8);
            assertThat(p.getCheckIntervalMs()).isEqualTo(60_000L);
        });
    }

    @Test
    void binds_under_prefix() {
        runner.withPropertyValues(
                        "engine.rule.observability.eval-error-rate-threshold=0.1",
                        "engine.rule.observability.trace-queue-full-threshold=0.9",
                        "engine.rule.observability.check-interval-ms=30000")
                .run(ctx -> {
                    ObservabilityAlarmProperties p = ctx.getBean(ObservabilityAlarmProperties.class);
                    assertThat(p.getEvalErrorRateThreshold()).isEqualTo(0.1);
                    assertThat(p.getTraceQueueFullThreshold()).isEqualTo(0.9);
                    assertThat(p.getCheckIntervalMs()).isEqualTo(30_000L);
                });
    }
}
