package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.audit.api.service.EffectivenessService;
import com.sstlfsj.rule.audit.api.service.EffectivenessService.EffectivenessReport;
import com.sstlfsj.rule.eval.api.service.OutcomeService;
import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import com.sstlfsj.rule.web.admin.dto.RecordOutcomesRequest;
import com.sstlfsj.rule.web.admin.dto.RecordOutcomesRequest.OutcomeItem;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutcomeControllerTest {

    private final OutcomeService outcomeService = mock(OutcomeService.class);
    private final EffectivenessService effectivenessService = mock(EffectivenessService.class);
    private final OutcomeController controller = new OutcomeController(outcomeService, effectivenessService);

    @Test
    void record_mapsItemsAndReturnsAccepted() {
        when(outcomeService.recordOutcomes(eq(1L), anyList())).thenReturn(2);
        RecordOutcomesRequest req = new RecordOutcomesRequest(1L, List.of(
                new OutcomeItem("e1", "FRAUD", null, Instant.parse("2026-06-18T00:00:00Z"), "ops", null),
                new OutcomeItem("e2", "NOT_FRAUD", null, Instant.parse("2026-06-18T00:00:00Z"), null, null)));

        ApiResponse<Map<String, Integer>> resp = controller.record(req);

        assertTrue(resp.success());
        assertEquals(2, resp.data().get("accepted"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutcomeRecord>> cap = ArgumentCaptor.forClass(List.class);
        verify(outcomeService).recordOutcomes(eq(1L), cap.capture());
        assertEquals("e1", cap.getValue().get(0).eventId());
        assertEquals("FRAUD", cap.getValue().get(0).outcomeLabel());
    }

    @Test
    void labels_returnsAvailableLabels() {
        when(outcomeService.availableLabels(9100L)).thenReturn(List.of("FRAUD", "NOT_FRAUD"));
        ApiResponse<List<String>> resp = controller.labels(9100L);
        assertTrue(resp.success());
        assertEquals(List.of("FRAUD", "NOT_FRAUD"), resp.data());
    }

    @Test
    void effectiveness_parsesInstantsAndDefaultsEmptyPositiveLabels() {
        when(effectivenessService.aggregate(any())).thenReturn(new EffectivenessReport(List.of()));

        ApiResponse<EffectivenessReport> resp = controller.effectiveness(
                1L, "s", "2026-06-01T00:00:00Z", "2026-06-19T00:00:00Z",
                null, EffectivenessService.Dimension.RULE_VERSION, EffectivenessService.Bucket.NONE);

        assertTrue(resp.success());
        verify(effectivenessService).aggregate(argThat(q ->
                q.positiveLabels().isEmpty()
                        && q.from().equals(Instant.parse("2026-06-01T00:00:00Z"))
                        && q.to().equals(Instant.parse("2026-06-19T00:00:00Z"))));
    }

    @Test
    void effectiveness_passesPositiveLabelsAndDimension() {
        when(effectivenessService.aggregate(any())).thenReturn(new EffectivenessReport(List.of()));

        controller.effectiveness(1L, "s", "2026-06-01T00:00:00Z", "2026-06-19T00:00:00Z",
                List.of("FRAUD", "CONFIRMED"), EffectivenessService.Dimension.DECISION,
                EffectivenessService.Bucket.DAY);

        verify(effectivenessService).aggregate(argThat(q ->
                q.positiveLabels().equals(List.of("FRAUD", "CONFIRMED"))
                        && q.dimension() == EffectivenessService.Dimension.DECISION
                        && q.bucket() == EffectivenessService.Bucket.DAY));
    }
}
