package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.FetchMode;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuleEngineClientAutoConfiguration.class));

    @Test
    void autoConfigures_ruleEngineClientBean() {
        runner.withPropertyValues(
                        "rule.sdk.server-url=http://localhost:19999",
                        "rule.sdk.tenant-id=t1",
                        "rule.sdk.fetch-mode=ALL")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                    ctx.getBean(RuleEngineClient.class).close();
                });
    }

    @Test
    void backOff_whenBeanAlreadyRegistered() {
        runner.withPropertyValues(
                        "rule.sdk.server-url=http://localhost:19999",
                        "rule.sdk.tenant-id=t1")
                .withBean(RuleEngineClient.class,
                        () -> RuleEngineClient.builder()
                                .serverUrl("http://custom:8080")
                                .tenantId("custom")
                                .build())
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                    ctx.getBean(RuleEngineClient.class).close();
                });
    }

    @Test
    void declaredMode_scenes_areRespected() {
        runner.withPropertyValues(
                        "rule.sdk.server-url=http://localhost:19999",
                        "rule.sdk.tenant-id=t1",
                        "rule.sdk.fetch-mode=DECLARED",
                        "rule.sdk.scenes=payment,fraud")
                .run(ctx -> {
                    SdkProperties props = ctx.getBean(SdkProperties.class);
                    assertThat(props.getFetchMode()).isEqualTo(FetchMode.DECLARED);
                    assertThat(props.getScenes()).containsExactly("payment", "fraud");
                    ctx.getBean(RuleEngineClient.class).close();
                });
    }
}
