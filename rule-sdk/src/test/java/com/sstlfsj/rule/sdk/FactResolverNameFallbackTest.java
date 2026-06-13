package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactResolverNameFallbackTest {

    static class Holder {
        public void m(@Fact Integer amount, @Metric Integer total) {}
    }

    @Test
    void emptyValue_fallsBackToParameterName() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, Integer.class);
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("amount", 8000)).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null,
                Map.of("total", new MetricValue(42, "INT", "FETCHED")), Instant.now());

        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);
        assertThat(args[0]).isEqualTo(8000);   // @Fact 无 value → 参数名 amount
        assertThat(args[1]).isEqualTo(42);     // @Metric 无 value → 参数名 total
    }
}
