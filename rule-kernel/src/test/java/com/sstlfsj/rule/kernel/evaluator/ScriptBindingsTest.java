package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.internal.evaluator.ScriptBindings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptBindingsTest {

    @SuppressWarnings("unchecked")
    @Test
    void buildsNamespacedReadonlyMap() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        RuleEvent event = new RuleEvent("t1", "scene", "TXN", "u1", "e1", now,
                Map.of("amount", 12000), Map.of(), EventSource.HTTP);
        Subject subject = new Subject("u1", SubjectType.USER, Map.of("level", "VIP"));
        Map<String, MetricValue> metrics = Map.of(
                "txn_cnt_1d", new MetricValue(53L, "LONG", "FETCHED"));
        EvalContext ctx = new EvalContext("t1", event, subject, metrics, now);

        Map<String, Object> b = ScriptBindings.from(ctx);

        assertThat((Map<String, Object>) b.get("metrics")).containsEntry("txn_cnt_1d", 53L);
        assertThat((Map<String, Object>) b.get("payload")).containsEntry("amount", 12000);
        assertThat((Map<String, Object>) b.get("subject")).containsEntry("level", "VIP");
        assertThat(b.get("now")).isEqualTo(now);
    }

    @SuppressWarnings("unchecked")
    @Test
    void nullMetricValueDoesNotNpe() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        RuleEvent event = new RuleEvent("t1", "scene", "TXN", "u1", "e1", now,
                Map.of(), Map.of(), EventSource.HTTP);
        java.util.Map<String, MetricValue> metrics = new java.util.HashMap<>();
        metrics.put("failed", MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL));
        EvalContext ctx = new EvalContext("t1", event, null, metrics, now);

        Map<String, Object> b = ScriptBindings.from(ctx);

        Map<String, Object> m = (Map<String, Object>) b.get("metrics");
        assertThat(m.containsKey("failed")).isTrue();
        assertThat(m.get("failed")).isNull();
    }

    @Test
    void nullSubjectYieldsEmptySubjectMap() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        RuleEvent event = new RuleEvent("t1", "scene", "TXN", "u1", "e1", now,
                Map.of(), Map.of(), EventSource.HTTP);
        EvalContext ctx = new EvalContext("t1", event, null, Map.of(), now);

        Map<String, Object> b = ScriptBindings.from(ctx);

        assertThat(b.get("subject")).isEqualTo(Map.of());
    }
}
