package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.api.TaskExecutionStatus;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskExecutionMapper;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import com.sstlfsj.rule.job.internal.runner.TaskExecutorRegistry;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 任务↔调度器中介 + 执行编排:cron 触发 runById → 重载 → dispatch → 写 scheduled_task_execution。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskScheduleManager {

    private final Scheduler scheduler;
    private final ScheduledTaskMapper taskMapper;
    private final ScheduledTaskExecutionMapper executionMapper;
    private final TaskExecutorRegistry executorRegistry;

    /**
     * 注册到调度器(cron 触发回 runById,config 触发时重载)。
     *
     * @param task 待注册任务
     */
    public void register(ScheduledTask task) {
        Long id = task.getId();
        scheduler.schedule(key(id), task.getCron(), () -> runById(id));
    }

    /**
     * 从调度器撤销。
     *
     * @param taskId 任务 id
     */
    public void unregister(Long taskId) {
        scheduler.unschedule(key(taskId));
    }

    /**
     * 手动触发一次(管理能力,不经调度器);DISABLED 任务亦可手动触发(便于禁用态验证)。
     *
     * @param taskId 任务 id
     * @return 执行记录
     */
    public ScheduledTaskExecution runOnce(Long taskId) {
        return doRun(taskMapper.selectById(taskId));
    }

    private void runById(Long taskId) {
        ScheduledTask latest = taskMapper.selectById(taskId);
        if (latest != null && latest.getStatus() == TaskStatus.ACTIVE) {
            doRun(latest);
        }
    }

    /** 执行编排:建 RUNNING 记录 → dispatch executor → 用 TaskRunResult 终结记录。 */
    private ScheduledTaskExecution doRun(ScheduledTask task) {
        ScheduledTaskExecution exec = new ScheduledTaskExecution();
        exec.setScheduledTaskId(task.getId());
        exec.setTenantId(task.getTenantId());
        exec.setTriggerAt(LocalDateTime.now());
        exec.setStatus(TaskExecutionStatus.RUNNING);
        exec.setProcessedCount(0);
        exec.setSuccessCount(0);
        exec.setErrorCount(0);
        executionMapper.insert(exec);
        try {
            TaskRunResult r = executorRegistry.dispatch(task, exec.getId());
            exec.setStatus(r.status());
            exec.setProcessedCount(r.processedCount());
            exec.setSuccessCount(r.successCount());
            exec.setErrorCount(r.errorCount());
            exec.setErrorSummary(r.errorSummary());
        } catch (RuntimeException e) {
            exec.setStatus(TaskExecutionStatus.FAILED);
            exec.setErrorSummary("执行异常: " + e.getMessage());
            log.warn("调度任务执行异常 taskId={}", task.getId(), e);
        }
        exec.setFinishedAt(LocalDateTime.now());
        executionMapper.updateById(exec);
        return exec;
    }

    private String key(Long taskId) {
        return "scheduled-task:" + taskId;
    }
}
