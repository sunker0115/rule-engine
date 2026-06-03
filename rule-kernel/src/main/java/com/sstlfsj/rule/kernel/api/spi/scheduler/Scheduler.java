package com.sstlfsj.rule.kernel.api.spi.scheduler;

/** 调度和管理周期性后台任务的 SPI 接口。 */
public interface Scheduler {
    void schedule(String jobCode, String cronExpression, Runnable task);
    void unschedule(String jobCode);
}
