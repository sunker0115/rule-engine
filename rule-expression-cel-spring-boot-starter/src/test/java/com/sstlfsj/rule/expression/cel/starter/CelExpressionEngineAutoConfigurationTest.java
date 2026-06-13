package com.sstlfsj.rule.expression.cel.starter;

import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.expression.cel.CelExpressionEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CelExpressionEngineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CelExpressionEngineAutoConfiguration.class));

    @Test
    void registersCelExpressionEngineBean() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CelExpressionEngine.class);
            assertThat(ctx.getBean(CelExpressionEngine.class).lang()).isEqualTo(ExpressionLang.CEL.tag());
        });
    }

    @Test
    void backsOffWhenCustomBeanPresent() {
        // 业务方已注册自定义引擎时,@ConditionalOnMissingBean 退让,仍只有一个 bean
        runner.withBean("custom", CelExpressionEngine.class, CelExpressionEngine::new)
                .run(ctx -> assertThat(ctx).hasSingleBean(CelExpressionEngine.class));
    }
}
