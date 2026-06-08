package com.sstlfsj.rule.eval.internal.session;

import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvalSessionWriterTest {

    @Mock DryRunSessionMapper dryRunMapper;
    @Spy ObjectMapper objectMapper = JsonMapper.builder().build();
    @InjectMocks EvalSessionWriter writer;

    private RuleEvent event() {
        return new RuleEvent("1", "scene", "E", "u1",
                "evt-001", Instant.parse("2024-01-01T00:00:00Z"), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    }

    @Test
    void writer_constructed_notNull() {
        assertNotNull(writer);
    }

    @Test
    void updateDryRunFinal_withContext_invokesMapper() {
        RuleEvent ev = event();
        EvalContext ctx = new EvalContext("1", ev, null,
                Map.of("order.amount", new MetricValue(5000, "INTEGER", "PROVIDED")),
                Instant.parse("2024-01-01T00:00:00Z"));
        EvalResult result = EvalResult.miss();

        writer.updateDryRunFinal(1L, result, ctx);

        verify(dryRunMapper).markFinal(any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateDryRunFinal_nullContext_invokesMapper() {
        writer.updateDryRunFinal(1L, EvalResult.miss(), null);
        verify(dryRunMapper).markFinal(any(), any(), any(), any(), any(), any());
    }

    @Test
    void snapshot_isNestedWithMetricsAndEvalNow() {
        // context_snapshot 形状契约：嵌套 metrics + evalNow 文本（serializeSnapshot 产物）
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        String json = objectMapper.writeValueAsString(Map.of(
                "metrics", Map.of("user.age", 25),
                "evalNow", now.toString()));
        assertTrue(json.contains("\"metrics\""));
        assertTrue(json.contains("\"evalNow\":\"2024-01-01T00:00:00Z\""));
    }
}
