package com.sstlfsj.rule.eval.internal.async;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 AuditProperties 绑定到 engine.rule.audit.* 命名空间，context-snapshot.enabled 默认 true(忠实重放开箱即用)。 */
class AuditPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(AuditProperties.class)
    static class Config {}

    @Test
    void contextSnapshotEnabled_defaultsToTrue() {
        runner.run(ctx -> assertThat(ctx.getBean(AuditProperties.class)
                .getContextSnapshot().isEnabled()).isTrue());
    }

    @Test
    void binds_underEngineRuleAuditContextSnapshotPrefix_canOptOut() {
        runner.withPropertyValues("engine.rule.audit.context-snapshot.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(AuditProperties.class)
                        .getContextSnapshot().isEnabled()).isFalse());
    }
}
