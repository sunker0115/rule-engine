package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import com.sstlfsj.rule.eval.internal.domain.SessionStatus;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DryRunPersisterTest {

    @Test
    void accept_insertsTerminalDryRunSessionAndTrace() {
        DryRunSessionMapper mapper = mock(DryRunSessionMapper.class);
        DryRunTraceWriter traceWriter = mock(DryRunTraceWriter.class);
        DryRunPersister persister = new DryRunPersister(mapper, traceWriter, JsonMapper.builder().build());
        RuleEvent ev = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e1").source(EventSource.HTTP).occurredAt(Instant.now()).build();

        persister.accept(new DryRunRecordedEvent(77L, ev, 99L, EvalResult.miss(), null, 0));

        ArgumentCaptor<DryRunSession> captor = ArgumentCaptor.forClass(DryRunSession.class);
        verify(mapper).insert(captor.capture());
        DryRunSession s = captor.getValue();
        assertThat(s.getId()).isEqualTo(77L);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.MISS);
        assertThat(s.getRuleVersionId()).isEqualTo(99L);
        verify(traceWriter).write(eq("1"), eq("77"), any());
    }

    @Test
    void startedAtFromContextNow_andEvalDurationMsFromEvent() {
        DryRunSessionMapper mapper = mock(DryRunSessionMapper.class);
        DryRunTraceWriter traceWriter = mock(DryRunTraceWriter.class);
        DryRunPersister persister = new DryRunPersister(mapper, traceWriter, JsonMapper.builder().build());
        RuleEvent ev = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e-dur").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        // 固定 evalNow：started_at 取真实评估起点 context.now()，duration 取事件
        Instant evalNow = Instant.parse("2026-06-09T01:02:03Z");
        EvalContext ctx = new EvalContext("1", ev, null, Map.of(), evalNow);

        persister.accept(new DryRunRecordedEvent(78L, ev, 99L, EvalResult.miss(), ctx, 42));

        ArgumentCaptor<DryRunSession> captor = ArgumentCaptor.forClass(DryRunSession.class);
        verify(mapper).insert(captor.capture());
        DryRunSession s = captor.getValue();
        LocalDateTime expectedStart = LocalDateTime.ofInstant(evalNow, ZoneId.systemDefault());
        assertThat(s.getStartedAt()).isEqualTo(expectedStart);
        assertThat(s.getEvalDurationMs()).isEqualTo(42);
        assertThat(s.getFinishedAt()).isEqualTo(expectedStart.plusNanos(42L * 1_000_000L));
    }
}
