package com.sstlfsj.rule.eval.internal.metric.sql;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 FetchResourceProperties 绑定到 engine.rule.fetch.* 命名空间（B26 前缀统一）。 */
class FetchResourcePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(FetchResourceProperties.class)
    static class Config {}

    @Test
    void binds_underEngineRuleFetchPrefix() {
        runner.withPropertyValues("engine.rule.fetch.timeout-ms=1234")
                .run(ctx -> assertThat(ctx.getBean(FetchResourceProperties.class).getTimeoutMs())
                        .isEqualTo(1234L));
    }

    @Test
    void timeout_defaultsTo800() {
        runner.run(ctx -> assertThat(ctx.getBean(FetchResourceProperties.class).getTimeoutMs())
                .isEqualTo(800L));
    }

    @Test
    void oldRuleFetchPrefix_doesNotBind() {
        // 旧前缀 rule.fetch.* 不再生效，保持默认（防止前缀回退）
        runner.withPropertyValues("rule.fetch.timeout-ms=1234")
                .run(ctx -> assertThat(ctx.getBean(FetchResourceProperties.class).getTimeoutMs())
                        .isEqualTo(800L));
    }
}
