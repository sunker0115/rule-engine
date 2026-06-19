package com.sstlfsj.rule.kernel.api.spi.scheduler;

/**
 * 通用调度后端（如 XXL-JOB）触发任务的回调 SPI。
 * 解耦 rule-job-xxl（不依赖 rule-job-svc）与 ScheduledTaskScheduleManager。
 */
@FunctionalInterface
public interface TaskRunCallback {
    /**
     * 按任务 id 运行一次（等同于 ScheduledTaskScheduleManager 的 runById）。
     *
     * @param taskId scheduled_task.id
     */
    void run(long taskId);
}
