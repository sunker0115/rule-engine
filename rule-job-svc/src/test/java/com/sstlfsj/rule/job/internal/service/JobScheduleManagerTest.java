package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobStatus;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import com.sstlfsj.rule.job.internal.runner.JobRunner;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobScheduleManagerTest {

    @Mock
    Scheduler scheduler;
    @Mock
    JobDefinitionMapper jobMapper;
    @Mock
    JobRunner jobRunner;

    @InjectMocks
    JobScheduleManager manager;

    private JobDefinition job(JobStatus status) {
        JobDefinition d = new JobDefinition();
        d.setId(7L);
        d.setCronExpression("* * * * * *");
        d.setStatus(status);
        return d;
    }

    /** 注册后捕获调度回调并触发；最新状态为 ACTIVE 时应运行 Job。 */
    @Test
    void triggerRunsJobWhenLatestStatusActive() {
        manager.register(job(JobStatus.ACTIVE));
        Runnable trigger = captureScheduledTask();

        when(jobMapper.selectById(7L)).thenReturn(job(JobStatus.ACTIVE));
        trigger.run();

        verify(jobRunner).run(any(JobDefinition.class));
    }

    /** 调度遗留触发时若最新状态已变为 DISABLED，门控应拦截不运行。 */
    @Test
    void triggerSkipsJobWhenLatestStatusDisabled() {
        manager.register(job(JobStatus.ACTIVE));
        Runnable trigger = captureScheduledTask();

        when(jobMapper.selectById(7L)).thenReturn(job(JobStatus.DISABLED));
        trigger.run();

        verify(jobRunner, never()).run(any());
    }

    /** Job 已被删除（selectById 返回 null）时不应运行。 */
    @Test
    void triggerSkipsJobWhenDefinitionMissing() {
        manager.register(job(JobStatus.ACTIVE));
        Runnable trigger = captureScheduledTask();

        when(jobMapper.selectById(7L)).thenReturn(null);
        trigger.run();

        verify(jobRunner, never()).run(any());
    }

    private Runnable captureScheduledTask() {
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(eq("job:7"), eq("* * * * * *"), taskCaptor.capture());
        return taskCaptor.getValue();
    }
}
