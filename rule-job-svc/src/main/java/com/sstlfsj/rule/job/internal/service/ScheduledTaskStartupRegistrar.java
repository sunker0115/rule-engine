package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** 启动期把全部 ACTIVE 任务注册到调度器(多实例由 XXL admin 单实例派发)。 */
@Slf4j
@Component
@RequiredArgsConstructor
class ScheduledTaskStartupRegistrar implements ApplicationRunner {

    private final ScheduledTaskMapper taskMapper;
    private final ScheduledTaskScheduleManager scheduleManager;

    @Override
    public void run(ApplicationArguments args) {
        List<ScheduledTask> active = taskMapper.findAllActive();
        active.forEach(scheduleManager::register);
        log.info("调度任务启动注册完成,注册 {} 个 ACTIVE 任务", active.size());
    }
}
