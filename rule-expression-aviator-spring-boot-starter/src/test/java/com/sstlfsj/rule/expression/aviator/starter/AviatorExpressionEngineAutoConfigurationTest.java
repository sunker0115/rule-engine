package com.sstlfsj.rule.expression.aviator.starter;

import com.sstlfsj.rule.expression.aviator.AviatorExpressionEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AviatorExpressionEngineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AviatorExpressionEngineAutoConfiguration.class));

    @Test
    void registersAviatorEngineBean() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AviatorExpressionEngine.class);
            assertThat(ctx.getBean(AviatorExpressionEngine.class).lang()).isEqualTo("AVIATOR");
        });
    }

    @Test
    void backsOffWhenCustomBeanPresent() {
        runner.withUserConfiguration(CustomEngineConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(AviatorExpressionEngine.class);
            assertThat(ctx.getBean(AviatorExpressionEngine.class)).isSameAs(CustomEngineConfig.CUSTOM);
        });
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class CustomEngineConfig {

        static final AviatorExpressionEngine CUSTOM = new AviatorExpressionEngine();

        @org.springframework.context.annotation.Bean
        public AviatorExpressionEngine customAviatorEngine() {
            return CUSTOM;
        }
    }
}
