package com.sstlfsj.rule.kernel.api.spi.scheduler;

import java.util.function.Consumer;

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

    /**
     * 注册广播 handler（code 全局唯一）。所有实例同时执行。
     *
     * @param code       广播标识，全局唯一
     * @param onEachNode 广播处理器，接收 {@link #triggerBroadcast} 透传的 param
     */
    void scheduleBroadcast(String code, Consumer<String> onEachNode);

    /**
     * 触发一次广播：所有实例同时执行对应 handler。
     *
     * @param code  已注册的广播 handler 标识
     * @param param 透传到 onEachNode 的业务 payload
     */
    void triggerBroadcast(String code, String param);
}
