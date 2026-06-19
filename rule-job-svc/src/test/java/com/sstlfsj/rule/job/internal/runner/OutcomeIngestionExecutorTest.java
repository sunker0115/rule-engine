package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.eval.api.service.IngestResult;
import com.sstlfsj.rule.eval.api.service.OutcomeIngestionService;
import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
import com.sstlfsj.rule.job.api.OutcomeIngestionConfig;
import com.sstlfsj.rule.job.api.TaskExecutionStatus;
import com.sstlfsj.rule.job.api.TaskRunContext;
import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutcomeIngestionExecutorTest {

    private final OutcomeIngestionService ingestionService = mock(OutcomeIngestionService.class);
    private final ScheduledTaskMapper taskMapper = mock(ScheduledTaskMapper.class);
    private final OutcomeIngestionExecutor executor = new OutcomeIngestionExecutor(ingestionService, taskMapper);

    private static final long RUN_ID = 100L;
    private static final long TASK_ID = 42L;
    private static final long TENANT_ID = 7L;
    private static final SqlOutcomeSourceConfig SOURCE =
            new SqlOutcomeSourceConfig("ds", "select event_id, outcome_label, outcome_value, labeled_at from t");

    @Test
    void watermarkAdvanced_writesBackConfig() {
        Instant oldWatermark = Instant.parse("2026-06-01T00:00:00Z");
        Instant newWatermark = Instant.parse("2026-06-19T00:00:00Z");
        OutcomeIngestionConfig config = new OutcomeIngestionConfig(SOURCE, oldWatermark);

        when(ingestionService.ingest(TENANT_ID, SOURCE, oldWatermark))
                .thenReturn(new IngestResult(3, newWatermark));
        ScheduledTask task = new ScheduledTask();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        TaskRunResult result = executor.execute(new TaskRunContext(RUN_ID, TASK_ID, TENANT_ID), config);

        verify(ingestionService).ingest(TENANT_ID, SOURCE, oldWatermark);
        ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertThat(captor.getValue().getConfig()).isInstanceOf(OutcomeIngestionConfig.class);
        OutcomeIngestionConfig written = (OutcomeIngestionConfig) captor.getValue().getConfig();
        assertThat(written.watermark()).isEqualTo(newWatermark);
        assertThat(written.source()).isEqualTo(SOURCE);

        assertThat(result.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(result.processedCount()).isEqualTo(3);
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.errorCount()).isZero();
    }

    @Test
    void watermarkUnchanged_noWriteBack() {
        Instant watermark = Instant.parse("2026-06-01T00:00:00Z");
        OutcomeIngestionConfig config = new OutcomeIngestionConfig(SOURCE, watermark);

        // 空批次:newWatermark == 旧 watermark,不应写回
        when(ingestionService.ingest(TENANT_ID, SOURCE, watermark))
                .thenReturn(new IngestResult(0, watermark));

        TaskRunResult result = executor.execute(new TaskRunContext(RUN_ID, TASK_ID, TENANT_ID), config);

        verify(taskMapper, never()).selectById(any());
        verify(taskMapper, never()).updateById(any(ScheduledTask.class));

        assertThat(result.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(result.processedCount()).isZero();
        assertThat(result.successCount()).isZero();
        assertThat(result.errorCount()).isZero();
    }
}
