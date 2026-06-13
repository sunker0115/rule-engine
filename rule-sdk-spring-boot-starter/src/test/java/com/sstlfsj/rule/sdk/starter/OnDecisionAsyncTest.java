package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class OnDecisionAsyncTest {

    static class AsyncHandler {
        final AtomicReference<String> thread = new AtomicReference<>();
        @OnDecision(value = "REVIEW", async = true)
        public void onReview() { thread.set(Thread.currentThread().getName()); }
    }

    @Test
    void async_runsOffCallerThread() {
        AsyncHandler h = new AsyncHandler();
        OnDecisionInvoker invoker = new OnDecisionInvoker(new FactResolver(), List.of(h),
                Executors.newSingleThreadExecutor(r -> new Thread(r, "ondecision-pool")));

        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null, Map.of(), Instant.now());
        invoker.accept(new DecisionFiredEvent("REVIEW", 1, null, "r", 1L, e, ctx));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(h.thread.get()).isEqualTo("ondecision-pool"));
    }
}
