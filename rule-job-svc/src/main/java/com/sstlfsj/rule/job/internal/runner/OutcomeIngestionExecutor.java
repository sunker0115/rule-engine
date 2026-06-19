package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.eval.api.service.IngestResult;
import com.sstlfsj.rule.eval.api.service.OutcomeIngestionService;
import com.sstlfsj.rule.job.api.OutcomeIngestionConfig;
import com.sstlfsj.rule.job.api.TaskExecutionStatus;
import com.sstlfsj.rule.job.api.TaskExecutor;
import com.sstlfsj.rule.job.api.TaskRunContext;
import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/** OUTCOME_INGESTION executor:调 eval-svc ingest 拉标签 upsert,游标前进则写回 scheduled_task.cursor 列(config 不动)。 */
@Component
@RequiredArgsConstructor
public class OutcomeIngestionExecutor implements TaskExecutor<OutcomeIngestionConfig> {

    private final OutcomeIngestionService ingestionService;
    private final ScheduledTaskMapper taskMapper;

    @Override public TaskType type() { return TaskType.OUTCOME_INGESTION; }
    @Override public Class<OutcomeIngestionConfig> configType() { return OutcomeIngestionConfig.class; }

    @Override
    public TaskRunResult execute(TaskRunContext ctx, OutcomeIngestionConfig config) {
        ScheduledTask task = taskMapper.selectById(ctx.taskId());
        if (task == null) {
            return new TaskRunResult(TaskExecutionStatus.FAILED, 0, 0, 0, "任务不存在: " + ctx.taskId());
        }
        Instant watermark = task.getCursor() == null ? null : Instant.parse(task.getCursor());

        IngestResult r = ingestionService.ingest(ctx.tenantId(), config.source(), watermark);

        // 游标前进才写回(只更新 cursor 列,config 不动)
        if (!Objects.equals(r.newWatermark(), watermark)) {
            task.setCursor(r.newWatermark() == null ? null : r.newWatermark().toString());
            taskMapper.updateById(task);
        }
        return new TaskRunResult(TaskExecutionStatus.SUCCESS, r.accepted(), r.accepted(), 0, null);
    }
}
