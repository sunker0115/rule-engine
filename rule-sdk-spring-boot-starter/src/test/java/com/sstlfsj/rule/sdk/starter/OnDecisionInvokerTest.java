package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OnDecisionInvokerTest {

    static class Handlers {
        final AtomicInteger reviewed = new AtomicInteger();
        @OnDecision("REVIEW")
        public void onReview(@Fact("number") Integer n) { reviewed.addAndGet(n); }
        @OnDecision("REVIEW")
        public void boom(@Fact("number") Integer n) { throw new RuntimeException("x"); }
    }

    private DecisionFiredEvent fired(String code) {
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("number", 7)).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null, Map.of(), Instant.now());
        return new DecisionFiredEvent(code, 1, null, "r", 1L, e, ctx);
    }

    @Test
    void invokes_matchingHandlers_andIsolatesHandlerFailure() {
        Handlers h = new Handlers();
        OnDecisionInvoker invoker = new OnDecisionInvoker(new FactResolver(), List.of(h));

        invoker.accept(fired("REVIEW"));   // onReview + boom 都匹配
        assertThat(h.reviewed.get()).isEqualTo(7);   // boom 抛异常被吞,onReview 仍执行

        invoker.accept(fired("OTHER"));    // 无匹配处理器 → no-op
        assertThat(h.reviewed.get()).isEqualTo(7);
    }
}
