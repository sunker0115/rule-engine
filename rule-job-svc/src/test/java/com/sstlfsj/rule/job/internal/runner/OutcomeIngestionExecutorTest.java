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
    void cursorAdvanced_writesBackCursorColumn() {
        Instant oldWatermark = Instant.parse("2026-06-01T00:00:00Z");
        Instant newWatermark = Instant.parse("2026-06-19T00:00:00Z");
        OutcomeIngestionConfig config = new OutcomeIngestionConfig(SOURCE);

        // 游标存于 task.cursor 列(非 config);executor 先 selectById 读 cursor 解析为 watermark
        ScheduledTask task = new ScheduledTask();
        task.setConfig(config);
        task.setCursor(oldWatermark.toString());
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(ingestionService.ingest(TENANT_ID, SOURCE, oldWatermark))
                .thenReturn(new IngestResult(3, newWatermark));

        TaskRunResult result = executor.execute(new TaskRunContext(RUN_ID, TASK_ID, TENANT_ID), config);

        // ingest 用 task.cursor 解析出的 watermark 调用,不是 config
        verify(ingestionService).ingest(TENANT_ID, SOURCE, oldWatermark);
        ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
        verify(taskMapper).updateById(captor.capture());
        // cursor 列前进到新 watermark;config 不动(仍是原 OutcomeIngestionConfig)
        assertThat(captor.getValue().getCursor()).isEqualTo(newWatermark.toString());
        assertThat(captor.getValue().getConfig()).isSameAs(config);

        assertThat(result.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(result.processedCount()).isEqualTo(3);
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.errorCount()).isZero();
    }

    @Test
    void cursorUnchanged_noWriteBack() {
        Instant watermark = Instant.parse("2026-06-01T00:00:00Z");
        OutcomeIngestionConfig config = new OutcomeIngestionConfig(SOURCE);

        ScheduledTask task = new ScheduledTask();
        task.setConfig(config);
        task.setCursor(watermark.toString());
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        // 空批次:newWatermark == 旧 cursor,不应写回
        when(ingestionService.ingest(TENANT_ID, SOURCE, watermark))
                .thenReturn(new IngestResult(0, watermark));

        TaskRunResult result = executor.execute(new TaskRunContext(RUN_ID, TASK_ID, TENANT_ID), config);

        verify(taskMapper, never()).updateById(any(ScheduledTask.class));

        assertThat(result.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(result.processedCount()).isZero();
        assertThat(result.successCount()).isZero();
        assertThat(result.errorCount()).isZero();
    }

    @Test
    void taskNotFound_returnsFailed() {
        OutcomeIngestionConfig config = new OutcomeIngestionConfig(SOURCE);
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);

        TaskRunResult result = executor.execute(new TaskRunContext(RUN_ID, TASK_ID, TENANT_ID), config);

        assertThat(result.status()).isEqualTo(TaskExecutionStatus.FAILED);
        verify(taskMapper, never()).updateById(any(ScheduledTask.class));
    }
}
