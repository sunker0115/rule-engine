package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 嵌套路径端到端:payload.order.amount 经 @Fact("order.amount") 注入并命中规则。 */
class NestedOrderRuleIT {

    @Test
    void nestedPayloadPath_drivesCondition() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(NestedOrderRule.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);

                    RuleEvent big = RuleEvent.builder().tenantId("").sceneCode("order-demo")
                            .eventType("order").subjectId("u").eventId("o-1").occurredAt(Instant.now())
                            .payload(Map.of("order", Map.of("amount", 20000)))
                            .source(EventSource.SDK).build();
                    assertThat(client.evaluate(big).ruleHit()).isTrue();

                    RuleEvent small = big.toBuilder().eventId("o-2")
                            .payload(Map.of("order", Map.of("amount", 5000))).build();
                    assertThat(client.evaluate(small).ruleHit()).isFalse();
                });
    }
}
