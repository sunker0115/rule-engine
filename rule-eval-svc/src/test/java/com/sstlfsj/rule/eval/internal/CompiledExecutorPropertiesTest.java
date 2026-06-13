package com.sstlfsj.rule.eval.internal;

import com.sstlfsj.rule.kernel.internal.evaluator.CompileErrorPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 CompiledExecutorProperties 绑定到 engine.rule.eval.compiled-executor.* 命名空间，默认关。 */
class CompiledExecutorPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(CompiledExecutorProperties.class)
    static class Config {}

    @Test
    void defaults_disabled_emptyWhitelist_fallback() {
        runner.run(ctx -> {
            CompiledExecutorProperties p = ctx.getBean(CompiledExecutorProperties.class);
            assertThat(p.isEnabled()).isFalse();
            assertThat(p.getRuleCodeWhitelist()).isEmpty();
            assertThat(p.getOnCompileError()).isEqualTo(CompileErrorPolicy.FALLBACK);
        });
    }

    @Test
    void binds_underEnginePrefix() {
        runner.withPropertyValues(
                        "engine.rule.eval.compiled-executor.enabled=true",
                        "engine.rule.eval.compiled-executor.rule-code-whitelist=RULE_A,RULE_B",
                        "engine.rule.eval.compiled-executor.on-compile-error=FAIL")
                .run(ctx -> {
                    CompiledExecutorProperties p = ctx.getBean(CompiledExecutorProperties.class);
                    assertThat(p.isEnabled()).isTrue();
                    assertThat(p.getRuleCodeWhitelist()).containsExactly("RULE_A", "RULE_B");
                    assertThat(p.getOnCompileError()).isEqualTo(CompileErrorPolicy.FAIL);
                });
    }
}
