package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskExecutionMapper;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import com.sstlfsj.rule.job.internal.runner.TaskExecutorRegistry;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.sstlfsj.rule.kernel.api.spi.task.TaskExecutionStatus;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunContext;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScheduledTaskScheduleManagerTest {

    private final Scheduler scheduler = mock(Scheduler.class);
    private final ScheduledTaskMapper taskMapper = mock(ScheduledTaskMapper.class);
    private final ScheduledTaskExecutionMapper execMapper = mock(ScheduledTaskExecutionMapper.class);
    private final TaskExecutorRegistry registry = mock(TaskExecutorRegistry.class);
    private final ScheduledTaskScheduleManager mgr =
            new ScheduledTaskScheduleManager(scheduler, taskMapper, execMapper, registry);

    @Test
    void runOnce_writesRunningThenFinalizes() {
        ScheduledTask task = new ScheduledTask();
        task.setId(5L); task.setTenantId(7L); task.setTaskType("TRIGGER");
        when(taskMapper.selectById(5L)).thenReturn(task);
        // 模拟 DB 自增主键：insert 时给 execution 赋 id（即 taskRunId），供 ctx 透传
        stubInsertAssignsId(42L);
        when(registry.dispatch(eq(task), any(TaskRunContext.class)))
                .thenReturn(new TaskRunResult(TaskExecutionStatus.SUCCESS, 3, 3, 0, null, null));

        ScheduledTaskExecution exec = mgr.runOnce(5L);

        verify(execMapper).insert(any(ScheduledTaskExecution.class));
        verify(execMapper).updateById(any(ScheduledTaskExecution.class));
        assertThat(exec.getStatus()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(exec.getProcessedCount()).isEqualTo(3);
        assertThat(exec.getFinishedAt()).isNotNull();
    }

    @Test
    void ctxCarriesCursor_andAdvancedCursorWrittenBack() {
        ScheduledTask task = new ScheduledTask();
        task.setId(5L); task.setTenantId(7L); task.setTaskType("OUTCOME_INGESTION");
        task.setRunCursor("2026-06-01T00:00:00Z");
        when(taskMapper.selectById(5L)).thenReturn(task);
        stubInsertAssignsId(42L);

        ArgumentCaptor<TaskRunContext> ctxCaptor = ArgumentCaptor.forClass(TaskRunContext.class);
        when(registry.dispatch(eq(task), ctxCaptor.capture()))
                .thenReturn(new TaskRunResult(TaskExecutionStatus.SUCCESS, 3, 3, 0, null,
                        "2026-06-19T00:00:00Z"));

        mgr.runOnce(5L);

        // ctx 带入旧游标
        assertThat(ctxCaptor.getValue().cursor()).isEqualTo("2026-06-01T00:00:00Z");
        assertThat(ctxCaptor.getValue().taskRunId()).isEqualTo(42L);
        // 游标前进 → 写回 run_cursor 列
        assertThat(task.getRunCursor()).isEqualTo("2026-06-19T00:00:00Z");
        verify(taskMapper).updateById(task);
    }

    @Test
    void cursorUnchanged_noWriteBack() {
        ScheduledTask task = new ScheduledTask();
        task.setId(5L); task.setTenantId(7L); task.setTaskType("OUTCOME_INGESTION");
        task.setRunCursor("2026-06-01T00:00:00Z");
        when(taskMapper.selectById(5L)).thenReturn(task);
        stubInsertAssignsId(42L);
        when(registry.dispatch(eq(task), any(TaskRunContext.class)))
                .thenReturn(new TaskRunResult(TaskExecutionStatus.SUCCESS, 0, 0, 0, null,
                        "2026-06-01T00:00:00Z"));

        mgr.runOnce(5L);

        verify(taskMapper, never()).updateById(any(ScheduledTask.class));
    }

    @Test
    void dispatchThrows_recordedFailed() {
        ScheduledTask task = new ScheduledTask();
        task.setId(5L); task.setTenantId(7L); task.setTaskType("TRIGGER");
        when(taskMapper.selectById(5L)).thenReturn(task);
        stubInsertAssignsId(42L);
        when(registry.dispatch(eq(task), any(TaskRunContext.class)))
                .thenThrow(new IllegalStateException("boom"));

        ScheduledTaskExecution exec = mgr.runOnce(5L);
        assertThat(exec.getStatus()).isEqualTo(TaskExecutionStatus.FAILED);
        assertThat(exec.getErrorSummary()).contains("boom");
    }

    @Test
    void runCallback_activeTask_delegatesToRunById() {
        ScheduledTask task = new ScheduledTask();
        task.setId(5L); task.setTenantId(7L); task.setTaskType("TRIGGER");
        task.setStatus(TaskStatus.ACTIVE);
        when(taskMapper.selectById(5L)).thenReturn(task);
        stubInsertAssignsId(42L);
        when(registry.dispatch(eq(task), any(TaskRunContext.class)))
                .thenReturn(new TaskRunResult(TaskExecutionStatus.SUCCESS, 1, 1, 0, null, null));

        mgr.run(5L);

        verify(execMapper).insert(any(ScheduledTaskExecution.class));
        verify(registry).dispatch(eq(task), any(TaskRunContext.class));
    }

    @Test
    void runCallback_nonActiveTask_doesNotRun() {
        ScheduledTask task = new ScheduledTask();
        task.setId(5L); task.setTenantId(7L); task.setTaskType("TRIGGER");
        task.setStatus(TaskStatus.DISABLED);
        when(taskMapper.selectById(5L)).thenReturn(task);

        mgr.run(5L);

        verify(execMapper, never()).insert(any(ScheduledTaskExecution.class));
        verify(registry, never()).dispatch(any(ScheduledTask.class), any(TaskRunContext.class));
    }

    /** 模拟 MyBatis 回填自增主键：insert 时给传入的 execution 赋指定 id。 */
    private void stubInsertAssignsId(long id) {
        when(execMapper.insert(any(ScheduledTaskExecution.class))).thenAnswer(inv -> {
            inv.<ScheduledTaskExecution>getArgument(0).setId(id);
            return 1;
        });
    }
}
