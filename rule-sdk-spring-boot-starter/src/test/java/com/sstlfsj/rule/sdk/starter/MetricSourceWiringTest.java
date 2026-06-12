package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import com.sstlfsj.rule.sdk.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetricSourceWiringTest {

    @RuleDef(code = "velocity", sceneCode = "risk", eventTypes = "txn",
            decisions = @DecisionBinding(code = "REVIEW", priority = 50))
    static class VelocityRule {
        @Condition
        public boolean suspicious(@Fact("amount") Integer amount,
                                  @Metric("recent_txn_count") Integer count) {
            return amount > 1000 && count >= 3;
        }
    }

    @Configuration
    static class Beans {
        @Bean VelocityRule rule() { return new VelocityRule(); }
        @Bean Metrics metrics() { return new Metrics(); }
    }

    static class Metrics {
        @MetricSource(value = "recent_txn_count", cacheTtlSeconds = 60)
        public long recent(@Fact("subjectId") String subjectId) {
            return "frequent-user".equals(subjectId) ? 5 : 1;
        }
    }

    @Test
    void metricSourceBean_isWired_andDrivesDecision() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuleEngineClientAutoConfiguration.class))
                .withUserConfiguration(Beans.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    assertThat(client.evaluate(txn("frequent-user", 2000)).ruleHit()).isTrue();
                    assertThat(client.evaluate(txn("normal-user", 2000)).ruleHit()).isFalse();
                });
    }

    private static RuleEvent txn(String subject, int amount) {
        return RuleEvent.builder().tenantId("").sceneCode("risk").eventType("txn")
                .subjectId(subject).eventId(UUID.randomUUID().toString()).occurredAt(Instant.now())
                .payload(Map.of("amount", amount)).source(EventSource.SDK).build();
    }
}
