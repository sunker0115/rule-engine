package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactResolverTest {

    static class Holder {
        public void m(@Fact("number") Integer number,
                      @Metric("total") Integer total,
                      @Fact("eventId") String eventId,
                      @Fact("missing") String missing) {}
    }

    private EvalContext ctx(Map<String, Object> payload, Map<String, MetricValue> metrics) {
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u1").eventId("evt-1").occurredAt(Instant.now())
                .payload(payload).source(EventSource.SDK).build();
        return new EvalContext("t", e, null, metrics, Instant.now());
    }

    @Test
    void resolves_payload_metric_metadata_andNullForMissing() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, Integer.class, String.class, String.class);
        EvalContext ctx = ctx(
                Map.of("number", 7),
                Map.of("total", new MetricValue(42, "INT", "FETCHED")));

        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);

        assertThat(args[0]).isEqualTo(7);        // payload
        assertThat(args[1]).isEqualTo(42);       // metric
        assertThat(args[2]).isEqualTo("evt-1");  // 元数据 eventId
        assertThat(args[3]).isNull();            // 全落空
    }

    @Test
    void metric_errorValue_injectsNull() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, Integer.class, String.class, String.class);
        EvalContext ctx = ctx(Map.of("number", 1), Map.of("total", MetricValue.error("METRIC_FETCH_FAIL")));
        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);
        assertThat(args[1]).isNull();
    }
}
