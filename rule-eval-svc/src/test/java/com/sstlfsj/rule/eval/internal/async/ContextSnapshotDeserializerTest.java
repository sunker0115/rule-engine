package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSnapshotDeserializerTest {

    private final ObjectMapper om = JsonMapper.builder().build();

    @Test
    void deserialize_parsesMetricsAndEvalNow() {
        String json = "{\"metrics\":{\"total\":200},\"evalNow\":\"2026-06-09T01:02:03Z\"}";
        ContextSnapshotDeserializer.Snapshot s = ContextSnapshotDeserializer.deserialize(om, json);
        assertThat(s.metrics()).containsEntry("total", 200);
        assertThat(s.evalNow()).isEqualTo(Instant.parse("2026-06-09T01:02:03Z"));
    }

    @Test
    void deserialize_nullOrBlank_returnsEmpty() {
        assertThat(ContextSnapshotDeserializer.deserialize(om, null).metrics()).isEmpty();
        assertThat(ContextSnapshotDeserializer.deserialize(om, "  ").evalNow()).isNull();
    }

    @Test
    void roundTrip_withSerializer() {
        RuleEvent ev = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("e")
                .subjectId("u").eventId("evt").occurredAt(Instant.now())
                .source(EventSource.SDK).build();
        Instant now = Instant.parse("2026-06-09T01:02:03Z");
        EvalContext ctx = new EvalContext("1", ev, null,
                Map.of("total", new MetricValue(8888, "NUMBER", "PROVIDED")), now);

        String json = ContextSnapshotSerializer.serialize(om, ctx);
        ContextSnapshotDeserializer.Snapshot s = ContextSnapshotDeserializer.deserialize(om, json);

        assertThat(s.metrics()).containsEntry("total", 8888);
        assertThat(s.evalNow()).isEqualTo(now);
    }
}
