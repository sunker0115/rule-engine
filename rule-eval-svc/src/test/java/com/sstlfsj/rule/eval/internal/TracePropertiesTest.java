package com.sstlfsj.rule.eval.internal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 TraceProperties 绑定到 engine.rule.trace.* 命名空间，默认 enabled=true。 */
class TracePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(TraceProperties.class)
    static class Config {}

    @Test
    void enabled_defaultsToTrue() {
        runner.run(ctx -> assertThat(ctx.getBean(TraceProperties.class).isEnabled())
                .isTrue());
    }

    @Test
    void binds_underEngineRuleTracePrefix() {
        runner.withPropertyValues("engine.rule.trace.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(TraceProperties.class).isEnabled())
                        .isFalse());
    }
}
