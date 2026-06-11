package com.sstlfsj.rule.samples.easyrules;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EasyRulesAnnotationIT {

    @Test
    void evenNumber_firesReviewActionsViaBothPaths() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(EvenNumberRule.class)
                .withBean(ReviewHandlers.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    ReviewHandlers h = ctx.getBean(ReviewHandlers.class);

                    RuleEvent even = RuleEvent.builder().tenantId("").sceneCode("number-demo")
                            .eventType("number").subjectId("u").eventId("n-4").occurredAt(Instant.now())
                            .payload(Map.of("number", 4)).source(EventSource.SDK).build();
                    EvalResult r = client.evaluate(even);

                    assertThat(r.ruleHit()).isTrue();
                    assertThat(h.eventCount()).isEqualTo(1);     // 甲
                    assertThat(h.onDecisionSum()).isEqualTo(4);  // 乙,注入 number=4

                    RuleEvent odd = even.toBuilder().eventId("n-5")
                            .payload(Map.of("number", 5)).build();
                    EvalResult r2 = client.evaluate(odd);
                    assertThat(r2.ruleHit()).isFalse();          // 奇数不命中,动作不触发
                    assertThat(h.eventCount()).isEqualTo(1);
                });
    }
}
