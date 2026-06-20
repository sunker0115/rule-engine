package com.sstlfsj.rule.eval.internal.outcomesource;

import com.sstlfsj.rule.eval.api.service.IngestResult;
import com.sstlfsj.rule.eval.api.service.OutcomePullResult;
import com.sstlfsj.rule.eval.api.service.OutcomeService;
import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutcomeIngestionServiceImplTest {

    @Mock
    private OutcomeSourceRegistry sourceRegistry;

    @Mock
    private OutcomeService outcomeService;

    @InjectMocks
    private OutcomeIngestionServiceImpl service;

    @Test
    void ingestPullsThenUpsertsAndReturnsAcceptedWithNewWatermark() {
        Long tenantId = 7L;
        SqlOutcomeSourceConfig source = new SqlOutcomeSourceConfig("ds", "SELECT 1");
        Instant watermark = Instant.parse("2026-06-01T00:00:00Z");
        Instant newWatermark = Instant.parse("2026-06-19T12:00:00Z");

        List<OutcomeRecord> records = List.of(
                new OutcomeRecord("evt-1", "FRAUD", new BigDecimal("100.00"), newWatermark, "src", null),
                new OutcomeRecord("evt-2", "OK", null, newWatermark, "src", null));
        OutcomePullResult pulled = new OutcomePullResult(records, newWatermark);

        when(sourceRegistry.pull(source, watermark, tenantId)).thenReturn(pulled);
        when(outcomeService.recordOutcomes(tenantId, records)).thenReturn(2);

        IngestResult result = service.ingest(tenantId, source, watermark);

        verify(sourceRegistry).pull(source, watermark, tenantId);
        verify(outcomeService).recordOutcomes(eq(tenantId), eq(records));
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.newWatermark()).isEqualTo(newWatermark);
    }
}
