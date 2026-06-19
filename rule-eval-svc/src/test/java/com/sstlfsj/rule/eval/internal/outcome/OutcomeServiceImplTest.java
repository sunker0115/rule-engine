package com.sstlfsj.rule.eval.internal.outcome;

import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OutcomeServiceImplTest {

    private final DecisionOutcomeMapper mapper = mock(DecisionOutcomeMapper.class);
    private final OutcomeServiceImpl service = new OutcomeServiceImpl(mapper);

    @Test
    void emptyList_shortCircuits_noDbCall() {
        assertEquals(0, service.recordOutcomes(1L, List.of()));
        verifyNoInteractions(mapper);
    }

    @Test
    void nullList_shortCircuits_noDbCall() {
        assertEquals(0, service.recordOutcomes(1L, null));
        verifyNoInteractions(mapper);
    }

    @Test
    void mapsRecordsAndUpserts() {
        Instant t = Instant.parse("2026-06-18T10:00:00Z");
        int n = service.recordOutcomes(7L, List.of(
                new OutcomeRecord("evt-1", "FRAUD", new BigDecimal("1280.50"), t, "ops", "chargeback")));
        assertEquals(1, n);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DecisionOutcome>> cap = ArgumentCaptor.forClass(List.class);
        verify(mapper).upsertBatch(cap.capture());
        DecisionOutcome row = cap.getValue().get(0);
        assertEquals(7L, row.getTenantId());
        assertEquals("evt-1", row.getEventId());
        assertEquals("FRAUD", row.getOutcomeLabel());
        assertEquals(new BigDecimal("1280.50"), row.getOutcomeValue());
        assertEquals("ops", row.getSource());
        assertEquals("chargeback", row.getOutcomeNote());
        assertNotNull(row.getLabeledAt());
    }
}
