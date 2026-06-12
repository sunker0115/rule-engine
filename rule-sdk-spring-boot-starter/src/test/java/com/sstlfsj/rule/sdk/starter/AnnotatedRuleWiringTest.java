package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedRuleWiringTest {

    static final AtomicInteger VIA_EVENT = new AtomicInteger();
    static final AtomicInteger VIA_ONDECISION = new AtomicInteger();

    @RuleDef(code = "even", sceneCode = "demo", eventTypes = "num",
            decisions = @DecisionBinding(code = "EVEN", priority = 1))
    static class EvenRule {
        @Condition
        public boolean isEven(@Fact("number") Integer n) { return n % 2 == 0; }
    }

    @Configuration
    static class Beans {
        @Bean EvenRule evenRule() { return new EvenRule(); }
        @Bean Handlers handlers() { return new Handlers(); }
    }

    static class Handlers {
        @OnDecision("EVEN")
        public void onEven(@Fact("number") Integer n) { VIA_ONDECISION.addAndGet(n); }
        @EventListener
        public void onAny(DecisionFiredEvent e) { if (e.decision("EVEN")) VIA_EVENT.incrementAndGet(); }
    }

    @Test
    void annotatedRule_firesBothSinks() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuleEngineClientAutoConfiguration.class))
                .withUserConfiguration(Beans.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    RuleEvent event = RuleEvent.builder().tenantId("").sceneCode("demo").eventType("num")
                            .subjectId("u").eventId("e1").occurredAt(Instant.now())
                            .payload(Map.of("number", 4)).source(EventSource.SDK).build();

                    EvalResult r = client.evaluate(event);
                    assertThat(r.ruleHit()).isTrue();
                    assertThat(VIA_EVENT.get()).isEqualTo(1);       // 甲:@EventListener
                    assertThat(VIA_ONDECISION.get()).isEqualTo(4);  // 乙:@OnDecision 注入 number
                });
    }
}
