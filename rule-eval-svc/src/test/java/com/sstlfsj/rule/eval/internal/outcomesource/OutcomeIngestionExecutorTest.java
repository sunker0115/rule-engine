package com.sstlfsj.rule.eval.internal.outcomesource;

import com.sstlfsj.rule.eval.api.service.IngestResult;
import com.sstlfsj.rule.eval.api.service.OutcomeIngestionConfig;
import com.sstlfsj.rule.eval.api.service.OutcomeIngestionService;
import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
import com.sstlfsj.rule.kernel.api.spi.task.TaskExecutionStatus;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunContext;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OUTCOME_INGESTION executor:游标经 ctx 入、result 出,不碰 DB(去中心化后从 job-svc 迁回 eval-svc)。 */
class OutcomeIngestionExecutorTest {

    private final OutcomeIngestionService ingestionService = mock(OutcomeIngestionService.class);
    private final OutcomeIngestionExecutor executor = new OutcomeIngestionExecutor(ingestionService);

    private static final long RUN_ID = 100L;
    private static final long TASK_ID = 42L;
    private static final long TENANT_ID = 7L;
    private static final SqlOutcomeSourceConfig SOURCE =
            new SqlOutcomeSourceConfig("ds", "select event_id, outcome_label, outcome_value, labeled_at from t");

    @Test
    void typeAndConfigType() {
        assertThat(executor.type()).isEqualTo("OUTCOME_INGESTION");
        assertThat(executor.configType()).isEqualTo(OutcomeIngestionConfig.class);
    }

    @Test
    void cursorAdvanced_newCursorIsNewWatermark() {
        Instant oldWatermark = Instant.parse("2026-06-01T00:00:00Z");
        Instant newWatermark = Instant.parse("2026-06-19T00:00:00Z");
        OutcomeIngestionConfig config = new OutcomeIngestionConfig(SOURCE);
        when(ingestionService.ingest(TENANT_ID, SOURCE, oldWatermark))
                .thenReturn(new IngestResult(3, newWatermark));

        TaskRunResult result = executor.execute(
                new TaskRunContext(RUN_ID, TASK_ID, TENANT_ID, oldWatermark.toString()), config);

        // ctx.cursor 解析为 watermark 调 ingest(不读 DB)
        verify(ingestionService).ingest(TENANT_ID, SOURCE, oldWatermark);
        assertThat(result.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(result.processedCount()).isEqualTo(3);
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.errorCount()).isZero();
        // newCursor = newWatermark.toString()
        assertThat(result.newCursor()).isEqualTo(newWatermark.toString());
    }

    @Test
    void nullCursor_firstFullPull() {
        Instant newWatermark = Instant.parse("2026-06-19T00:00:00Z");
        OutcomeIngestionConfig config = new OutcomeIngestionConfig(SOURCE);
        when(ingestionService.ingest(TENANT_ID, SOURCE, null))
                .thenReturn(new IngestResult(5, newWatermark));

        TaskRunResult result = executor.execute(
                new TaskRunContext(RUN_ID, TASK_ID, TENANT_ID, null), config);

        verify(ingestionService).ingest(TENANT_ID, SOURCE, null);
        assertThat(result.newCursor()).isEqualTo(newWatermark.toString());
        assertThat(result.processedCount()).isEqualTo(5);
    }

    @Test
    void emptyBatch_newCursorFallsBackToOldCursor() {
        String cursor = "2026-06-01T00:00:00Z";
        OutcomeIngestionConfig config = new OutcomeIngestionConfig(SOURCE);
        // newWatermark null(空批次)→ newCursor 退回原 cursor,不前进
        when(ingestionService.ingest(TENANT_ID, SOURCE, Instant.parse(cursor)))
                .thenReturn(new IngestResult(0, null));

        TaskRunResult result = executor.execute(
                new TaskRunContext(RUN_ID, TASK_ID, TENANT_ID, cursor), config);

        assertThat(result.newCursor()).isEqualTo(cursor);
        assertThat(result.processedCount()).isZero();
    }
}
