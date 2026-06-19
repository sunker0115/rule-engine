package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.api.TaskExecutionStatus;
import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskExecutionMapper;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import com.sstlfsj.rule.job.internal.runner.TaskExecutorRegistry;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import org.junit.jupiter.api.Test;

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
        task.setId(5L); task.setTenantId(7L); task.setTaskType(TaskType.TRIGGER);
        when(taskMapper.selectById(5L)).thenReturn(task);
        // 模拟 DB 自增主键：insert 时给 execution 赋 id（即 taskRunId），供 dispatch 透传
        stubInsertAssignsId(42L);
        when(registry.dispatch(eq(task), eq(42L)))
                .thenReturn(new TaskRunResult(TaskExecutionStatus.SUCCESS, 3, 3, 0, null));

        ScheduledTaskExecution exec = mgr.runOnce(5L);

        verify(execMapper).insert(any(ScheduledTaskExecution.class));
        verify(execMapper).updateById(any(ScheduledTaskExecution.class));
        assertThat(exec.getStatus()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(exec.getProcessedCount()).isEqualTo(3);
        assertThat(exec.getFinishedAt()).isNotNull();
    }

    @Test
    void dispatchThrows_recordedFailed() {
        ScheduledTask task = new ScheduledTask();
        task.setId(5L); task.setTenantId(7L); task.setTaskType(TaskType.TRIGGER);
        when(taskMapper.selectById(5L)).thenReturn(task);
        stubInsertAssignsId(42L);
        when(registry.dispatch(eq(task), eq(42L))).thenThrow(new IllegalStateException("boom"));

        ScheduledTaskExecution exec = mgr.runOnce(5L);
        assertThat(exec.getStatus()).isEqualTo(TaskExecutionStatus.FAILED);
        assertThat(exec.getErrorSummary()).contains("boom");
    }

    /** 模拟 MyBatis 回填自增主键：insert 时给传入的 execution 赋指定 id。 */
    private void stubInsertAssignsId(long id) {
        when(execMapper.insert(any(ScheduledTaskExecution.class))).thenAnswer(inv -> {
            inv.<ScheduledTaskExecution>getArgument(0).setId(id);
            return 1;
        });
    }
}
