package com.sstlfsj.rule.config.internal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 MetricProperties 绑定 engine.rule.metric.* 命名空间，默认 TTL=60。 */
class MetricPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(MetricProperties.class)
    static class Config {}

    @Test
    void defaultCacheTtl_defaultsTo60() {
        runner.run(ctx -> assertThat(ctx.getBean(MetricProperties.class)
                .getDefaultCacheTtlSeconds()).isEqualTo(60));
    }

    @Test
    void binds_underEngineRuleMetricPrefix() {
        runner.withPropertyValues("engine.rule.metric.default-cache-ttl-seconds=120")
                .run(ctx -> assertThat(ctx.getBean(MetricProperties.class)
                        .getDefaultCacheTtlSeconds()).isEqualTo(120));
    }
}
