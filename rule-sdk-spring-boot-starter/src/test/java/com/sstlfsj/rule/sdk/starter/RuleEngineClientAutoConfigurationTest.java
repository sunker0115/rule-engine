package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.EvalResultListener;
import com.sstlfsj.rule.sdk.FetchMode;
import com.sstlfsj.rule.sdk.InlineRuleSpec;
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

    @Test
    void ruleFile_mode_loadsFromClasspath() {
        // rules/test-rule.json 来自 rule-sdk 测试资源，starter 测试 classpath 中需存在
        runner.withPropertyValues("rule.sdk.rule-files=rules/test-rule.json")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                    ctx.getBean(RuleEngineClient.class).close();
                });
    }

    @Test
    void ruleFiles_propertyBinding() {
        runner.withPropertyValues(
                        "rule.sdk.rule-files=rules/test-rule.json")
                .run(ctx -> {
                    SdkProperties props = ctx.getBean(SdkProperties.class);
                    assertThat(props.getRuleFiles()).containsExactly("rules/test-rule.json");
                });
    }

    @RuleDef(id = 10L, tenantId = "t1", sceneCode = "test",
             trigger = "TEST_EVENT",
             decisions = @DecisionBinding(code = "PASS", priority = 10))
    static class TestInlineRule implements InlineRuleSpec {
        @Override public Condition condition() { return Condition.always(); }
    }

    @Test
    void inlineRuleSpec_bean_autoLoaded() {
        runner.withBean(TestInlineRule.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                    ctx.getBean(RuleEngineClient.class).close();
                });
    }

    /** @ConditionType 标注但未实现 ConditionEvaluator 的 Bean 应被跳过，不报错。 */
    @ConditionType("NOT_AN_EVALUATOR")
    static class NotAnEvaluator {}

    @Test
    void conditionType_nonEvaluatorBean_skipped_noError() {
        runner.withPropertyValues("rule.sdk.rule-files=rules/test-rule.json")
                .withBean(NotAnEvaluator.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                    ctx.getBean(RuleEngineClient.class).close();
                });
    }

    @Test
    void evalResultListener_bean_autoInjected() {
        runner.withPropertyValues(
                        "rule.sdk.server-url=http://localhost:19999",
                        "rule.sdk.tenant-id=t1",
                        "rule.sdk.poll-interval=3600s")
                .withBean(EvalResultListener.class, () -> (ev, res) -> {})
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                    ctx.getBean(RuleEngineClient.class).close();
                });
    }
}
