package com.sstlfsj.rule.kernel.api.spi.scheduler;

/** Schedules and manages recurring background jobs. */
public interface Scheduler {
    void schedule(String jobCode, String cronExpression, Runnable task);
    void unschedule(String jobCode);
}
