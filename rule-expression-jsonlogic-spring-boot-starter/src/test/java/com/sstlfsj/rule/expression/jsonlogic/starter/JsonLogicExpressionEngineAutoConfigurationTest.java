package com.sstlfsj.rule.expression.jsonlogic.starter;

import com.sstlfsj.rule.expression.jsonlogic.JsonLogicExpressionEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JsonLogicExpressionEngineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JsonLogicExpressionEngineAutoConfiguration.class));

    @Test
    void registersJsonLogicEngineBean() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(JsonLogicExpressionEngine.class);
            assertThat(ctx.getBean(JsonLogicExpressionEngine.class).lang()).isEqualTo("JSONLOGIC");
        });
    }

    @Test
    void backsOffWhenCustomBeanPresent() {
        runner.withUserConfiguration(CustomEngineConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(JsonLogicExpressionEngine.class);
            assertThat(ctx.getBean(JsonLogicExpressionEngine.class)).isSameAs(CustomEngineConfig.CUSTOM);
        });
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class CustomEngineConfig {

        static final JsonLogicExpressionEngine CUSTOM = new JsonLogicExpressionEngine();

        @org.springframework.context.annotation.Bean
        public JsonLogicExpressionEngine customJsonLogicEngine() {
            return CUSTOM;
        }
    }
}
