package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import com.sstlfsj.rule.sdk.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecideScoreWiringTest {

    @RuleDef(code = "risk", sceneCode = "demo", trigger = "evt", decisions = {
            @DecisionBinding(code = "PASS", priority = 10),
            @DecisionBinding(code = "REJECT", priority = 90)})
    static class RiskRule {
        @Decide public String decide(@Fact("amount") Integer amount) {
            return amount > 5000 ? "REJECT" : "PASS";
        }
    }

    @Test
    void decideRule_isWiredAndEvaluated() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuleEngineClientAutoConfiguration.class))
                .withBean(RiskRule.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    RuleEvent e = RuleEvent.builder().tenantId("").sceneCode("demo").eventType("evt")
                            .subjectId("u").eventId("e1").occurredAt(Instant.now())
                            .payload(Map.of("amount", 8000)).source(EventSource.SDK).build();
                    EvalResult r = client.evaluate(e);
                    assertThat(r.ruleHit()).isTrue();
                    assertThat(r.finalDecision().code()).isEqualTo("REJECT");
                });
    }
}
