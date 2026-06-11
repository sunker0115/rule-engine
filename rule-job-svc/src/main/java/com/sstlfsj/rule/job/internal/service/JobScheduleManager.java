package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobStatus;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import com.sstlfsj.rule.job.internal.runner.JobRunner;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Job 与调度器的注册中介：把 JobDefinition 注册/撤销到 {@link Scheduler}。
 *
 * <p>调度触发时按 jobId 重新查最新定义再运行，确保 cron 外的运行时字段（subjectQuery /
 * status）取当前值，且 DISABLED 的 Job 即使遗留触发也不执行。
 */
@Component
@RequiredArgsConstructor
class JobScheduleManager {

    private final Scheduler scheduler;
    private final JobDefinitionMapper jobMapper;
    private final JobRunner jobRunner;

    void register(JobDefinition def) {
        Long jobId = def.getId();
        scheduler.schedule(key(jobId), def.getCronExpression(), () -> runById(jobId));
    }

    void unregister(Long jobId) {
        scheduler.unschedule(key(jobId));
    }

    private void runById(Long jobId) {
        JobDefinition latest = jobMapper.selectById(jobId);
        if (latest != null && latest.getStatus() == JobStatus.ACTIVE) {
            jobRunner.run(latest);
        }
    }

    private String key(Long jobId) {
        return "job:" + jobId;
    }
}
