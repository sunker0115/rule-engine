package com.sstlfsj.rule.expression.jexl.starter;

import com.sstlfsj.rule.expression.jexl.JexlExpressionEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JexlExpressionEngineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JexlExpressionEngineAutoConfiguration.class));

    @Test
    void registersJexlEngineBean() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(JexlExpressionEngine.class);
            assertThat(ctx.getBean(JexlExpressionEngine.class).lang()).isEqualTo("JEXL");
        });
    }

    @Test
    void backsOffWhenCustomBeanPresent() {
        runner.withUserConfiguration(CustomEngineConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(JexlExpressionEngine.class);
            assertThat(ctx.getBean(JexlExpressionEngine.class)).isSameAs(CustomEngineConfig.CUSTOM);
        });
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class CustomEngineConfig {

        static final JexlExpressionEngine CUSTOM = new JexlExpressionEngine();

        @org.springframework.context.annotation.Bean
        public JexlExpressionEngine customJexlEngine() {
            return CUSTOM;
        }
    }
}
