package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;
import com.sstlfsj.rule.eval.internal.domain.EvaluationContextSnapshot;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSnapshotDeserializerTest {

    @Test
    void deserialize_parsesMetricsAndEvalNow() {
        ContextSnapshotDeserializer.Snapshot s = ContextSnapshotDeserializer.deserialize(
                new EvaluationContextSnapshot(Map.of("total", 200), Instant.parse("2026-06-09T01:02:03Z")));
        assertThat(s.metrics()).containsEntry("total", 200);
        assertThat(s.evalNow()).isEqualTo(Instant.parse("2026-06-09T01:02:03Z"));
    }

    @Test
    void deserialize_nullOrBlank_returnsEmpty() {
        assertThat(ContextSnapshotDeserializer.deserialize(null).metrics()).isEmpty();
        assertThat(ContextSnapshotDeserializer.deserialize(null).evalNow()).isNull();
    }

    @Test
    void roundTrip_withSerializer() {
        RuleEvent ev = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("e")
                .subjectId("u").eventId("evt").occurredAt(Instant.now())
                .source(EventSource.SDK).build();
        Instant now = Instant.parse("2026-06-09T01:02:03Z");
        EvalContext ctx = new EvalContext("1", ev, null,
                Map.of("total", new MetricValue(8888, "NUMBER", "PROVIDED")), now);

        EvaluationContextSnapshot snapshot = ContextSnapshotSerializer.serialize(ctx);
        ContextSnapshotDeserializer.Snapshot s = ContextSnapshotDeserializer.deserialize(snapshot);

        assertThat(s.metrics()).containsEntry("total", 8888);
        assertThat(s.evalNow()).isEqualTo(now);
    }
}
