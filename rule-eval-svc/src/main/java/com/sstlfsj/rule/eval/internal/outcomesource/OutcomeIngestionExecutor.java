package com.sstlfsj.rule.eval.internal.outcomesource;

import com.sstlfsj.rule.eval.api.service.IngestResult;
import com.sstlfsj.rule.eval.api.service.OutcomeIngestionConfig;
import com.sstlfsj.rule.eval.api.service.OutcomeIngestionService;
import com.sstlfsj.rule.kernel.api.spi.task.TaskExecutionStatus;
import com.sstlfsj.rule.kernel.api.spi.task.TaskExecutor;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunContext;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * OUTCOME_INGESTION executor(落 eval-svc,去中心化):调 ingest 拉标签 upsert。
 *
 * <p>游标(watermark)经 {@link TaskRunContext#cursor()} 入、{@link TaskRunResult#newCursor()} 出,
 * executor 不碰 scheduled_task 表(写回由 job-svc 调度框架按 newCursor 完成)。
 */
@Component
@RequiredArgsConstructor
public class OutcomeIngestionExecutor implements TaskExecutor<OutcomeIngestionConfig> {

    private final OutcomeIngestionService ingestionService;

    @Override
    public String type() {
        return "OUTCOME_INGESTION";
    }

    @Override
    public Class<OutcomeIngestionConfig> configType() {
        return OutcomeIngestionConfig.class;
    }

    @Override
    public TaskRunResult execute(TaskRunContext ctx, OutcomeIngestionConfig config) {
        Instant watermark = ctx.cursor() == null ? null : Instant.parse(ctx.cursor());
        IngestResult r = ingestionService.ingest(ctx.tenantId(), config.source(), watermark);
        String newCursor = r.newWatermark() == null ? ctx.cursor() : r.newWatermark().toString();
        return new TaskRunResult(TaskExecutionStatus.SUCCESS, r.accepted(), r.accepted(), 0, null, newCursor);
    }
}
