package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** 启动期把所有 ACTIVE Job 注册到调度器（单实例语义，多实例需选主，见 §3.10）。 */
@Component
@RequiredArgsConstructor
class JobStartupRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobStartupRegistrar.class);

    private final JobDefinitionMapper jobMapper;
    private final JobScheduleManager scheduleManager;

    @Override
    public void run(ApplicationArguments args) {
        List<JobDefinition> active = jobMapper.findAllActive();
        active.forEach(scheduleManager::register);
        log.info("Job 启动注册完成，注册 {} 个 ACTIVE Job（单实例调度，多实例需选主）", active.size());
    }
}
