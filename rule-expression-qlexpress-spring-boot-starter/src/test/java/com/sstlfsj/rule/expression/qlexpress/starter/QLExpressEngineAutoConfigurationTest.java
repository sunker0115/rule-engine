package com.sstlfsj.rule.expression.qlexpress.starter;

import com.sstlfsj.rule.expression.qlexpress.QLExpressEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class QLExpressEngineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QLExpressEngineAutoConfiguration.class));

    @Test
    void registersQLExpressEngineBean() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(QLExpressEngine.class);
            assertThat(ctx.getBean(QLExpressEngine.class).lang()).isEqualTo("QLEXPRESS");
        });
    }

    @Test
    void backsOffWhenCustomBeanPresent() {
        runner.withUserConfiguration(CustomEngineConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(QLExpressEngine.class);
            assertThat(ctx.getBean(QLExpressEngine.class)).isSameAs(CustomEngineConfig.CUSTOM);
        });
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class CustomEngineConfig {

        static final QLExpressEngine CUSTOM = new QLExpressEngine();

        @org.springframework.context.annotation.Bean
        public QLExpressEngine customQLExpressEngine() {
            return CUSTOM;
        }
    }
}
