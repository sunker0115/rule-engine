package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineClientContextListenerTest {

    @Test
    void evaluate_invokesContextListener_withNonNullContextOnCandidate() {
        // payloadGt amount>1000 → EVEN 决策;命中后 context 非空
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t").sceneCode("s").code("r").version(1L)
                .conditionAst(Condition.payloadGt("amount", 1000).toAst())
                .addTriggerEventType("evt")
                .addDecisionBinding("HIT", 1)
                .build();

        AtomicReference<EvalContext> seen = new AtomicReference<>();
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .decisionContextListener((e, r, c) -> seen.set(c))
                .build()) {

            RuleEvent event = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                    .subjectId("u").eventId("e1").occurredAt(Instant.now())
                    .payload(Map.of("amount", 5000)).source(EventSource.SDK).build();

            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isTrue();
            assertThat(seen.get()).isNotNull();   // 带 context 回调被触发
        }
    }
}
