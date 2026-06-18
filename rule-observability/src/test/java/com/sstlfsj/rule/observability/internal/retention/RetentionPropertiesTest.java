package com.sstlfsj.rule.observability.internal.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 RetentionProperties 绑定 engine.rule.retention.* 子集，默认 enabled/30/1000。 */
class RetentionPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(RetentionProperties.class)
    static class Config {}

    @Test
    void defaults_matchHardcodedValues() {
        runner.run(ctx -> {
            RetentionProperties props = ctx.getBean(RetentionProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getNodeTraceDays()).isEqualTo(30);
            assertThat(props.getBatchSize()).isEqualTo(1000);
        });
    }

    @Test
    void binds_underEngineRuleRetentionPrefix() {
        runner.withPropertyValues(
                        "engine.rule.retention.enabled=false",
                        "engine.rule.retention.node-trace-days=60",
                        "engine.rule.retention.batch-size=500")
                .run(ctx -> {
                    RetentionProperties props = ctx.getBean(RetentionProperties.class);
                    assertThat(props.isEnabled()).isFalse();
                    assertThat(props.getNodeTraceDays()).isEqualTo(60);
                    assertThat(props.getBatchSize()).isEqualTo(500);
                });
    }
}
