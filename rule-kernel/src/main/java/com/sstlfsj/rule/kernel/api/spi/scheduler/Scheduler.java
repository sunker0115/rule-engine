package com.sstlfsj.rule.kernel.api.spi.scheduler;

/** 调度和管理周期性后台任务的 SPI 接口。 */
public interface Scheduler {
    /**
     * 注册一个按 cron 表达式周期执行的后台任务。
     *
     * @param jobCode        任务唯一编码
     * @param cronExpression cron 调度表达式
     * @param task           待执行的任务体
     */
    void schedule(String jobCode, String cronExpression, Runnable task);

    /**
     * 取消已注册的周期任务。
     *
     * @param jobCode 任务唯一编码
     */
    void unschedule(String jobCode);
}
