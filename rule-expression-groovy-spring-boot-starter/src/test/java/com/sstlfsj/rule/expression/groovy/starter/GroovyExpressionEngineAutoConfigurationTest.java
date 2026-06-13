package com.sstlfsj.rule.expression.groovy.starter;

import com.sstlfsj.rule.expression.groovy.GroovyExpressionEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GroovyExpressionEngineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GroovyExpressionEngineAutoConfiguration.class));

    @Test
    void registersGroovyEngineBean() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(GroovyExpressionEngine.class);
            assertThat(ctx.getBean(GroovyExpressionEngine.class).lang()).isEqualTo("GROOVY");
        });
    }

    @Test
    void backsOffWhenCustomBeanPresent() {
        runner.withUserConfiguration(CustomEngineConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(GroovyExpressionEngine.class);
            assertThat(ctx.getBean(GroovyExpressionEngine.class)).isSameAs(CustomEngineConfig.CUSTOM);
        });
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class CustomEngineConfig {

        static final GroovyExpressionEngine CUSTOM = new GroovyExpressionEngine();

        @org.springframework.context.annotation.Bean
        public GroovyExpressionEngine customGroovyEngine() {
            return CUSTOM;
        }
    }
}
