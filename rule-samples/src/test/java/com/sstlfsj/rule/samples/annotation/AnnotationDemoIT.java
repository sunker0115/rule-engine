package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注解规则即代码端到端验证:starter 自动装配 {@link LargeTradeRule} 的 {@code @Condition} 规则,
 * 命中 REVIEW 后甲({@code @EventListener})与乙({@code @OnDecision})两条动作路径各触发一次。
 */
class AnnotationDemoIT {

    @Test
    void largeTrade_firesReviewViaBothActionPaths() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(LargeTradeRule.class)
                .withBean(ReviewHandlers.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    ReviewHandlers h = ctx.getBean(ReviewHandlers.class);

                    // 大额 + 营业时段 → 命中 REVIEW
                    EvalResult hit = client.evaluate(trade("t-1", 8000, 10));
                    assertThat(hit.ruleHit()).isTrue();
                    assertThat(hit.finalDecision().code()).isEqualTo("REVIEW");
                    assertThat(h.eventCount()).isEqualTo(1);        // 甲 @EventListener
                    assertThat(h.onDecisionCount()).isEqualTo(1);   // 乙 @OnDecision

                    // 大额但非营业时段 → 不命中,动作不再触发
                    EvalResult miss = client.evaluate(trade("t-2", 8000, 3));
                    assertThat(miss.ruleHit()).isFalse();
                    assertThat(h.eventCount()).isEqualTo(1);
                    assertThat(h.onDecisionCount()).isEqualTo(1);
                });
    }

    private static RuleEvent trade(String eventId, int amount, int hour) {
        return RuleEvent.builder().tenantId("").sceneCode("merchant-trade").eventType("trade")
                .subjectId("merchant-1").eventId(eventId).occurredAt(Instant.now())
                .payload(Map.of("amount", amount, "hour", hour)).source(EventSource.SDK).build();
    }
}
