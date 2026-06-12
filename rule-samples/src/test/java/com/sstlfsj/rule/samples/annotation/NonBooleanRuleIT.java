package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 非 boolean 注解规则端到端:@Score 信用分分档 + @Decide 多分支风控。 */
class NonBooleanRuleIT {

    @Test
    void scoreAndDecide_produceDecisions() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(CreditScoreRule.class)
                .withBean(RiskDecideRule.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);

                    RuleEvent credit = RuleEvent.builder().tenantId("").sceneCode("credit-demo")
                            .eventType("apply").subjectId("u").eventId("c1").occurredAt(Instant.now())
                            .payload(Map.of("score", 72)).source(EventSource.SDK).build();
                    EvalResult cr = client.evaluate(credit);
                    assertThat(cr.finalDecision().code()).isEqualTo("MANUAL_REVIEW");
                    assertThat(cr.score()).isEqualTo(72.0);

                    RuleEvent risk = RuleEvent.builder().tenantId("").sceneCode("risk-demo")
                            .eventType("txn").subjectId("u").eventId("r1").occurredAt(Instant.now())
                            .payload(Map.of("amount", 99999)).source(EventSource.SDK).build();
                    assertThat(client.evaluate(risk).finalDecision().code()).isEqualTo("BLOCK");
                });
    }
}
