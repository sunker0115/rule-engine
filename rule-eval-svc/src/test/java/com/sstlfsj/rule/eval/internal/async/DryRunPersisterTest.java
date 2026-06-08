package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

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

        persister.accept(new DryRunRecorded(77L, ev, 99L, EvalResult.miss(), null));

        ArgumentCaptor<DryRunSession> captor = ArgumentCaptor.forClass(DryRunSession.class);
        verify(mapper).insert(captor.capture());
        DryRunSession s = captor.getValue();
        assertThat(s.getId()).isEqualTo(77L);
        assertThat(s.getStatus()).isEqualTo("MISS");
        assertThat(s.getRuleVersionId()).isEqualTo(99L);
        verify(traceWriter).write(eq("1"), eq("77"), any());
    }
}
