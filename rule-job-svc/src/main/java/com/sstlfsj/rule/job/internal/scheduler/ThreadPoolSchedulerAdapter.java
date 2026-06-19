package com.sstlfsj.rule.job.internal.scheduler;

import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 进程内 Scheduler 实现：基于 {@link ThreadPoolTaskScheduler} + {@link CronTrigger}。
 *
 * <p>单实例语义——多实例部署会重复触发，这是已知限制（见 docs/01-concepts.md §3.10）；
 * 需要 HA 时替换为选主或外部调度（xxl-job），业务侧 scheduled_task / scheduled_task_execution 不变。
 */
public class ThreadPoolSchedulerAdapter implements Scheduler, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolSchedulerAdapter.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final java.util.Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public ThreadPoolSchedulerAdapter() {
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setThreadNamePrefix("job-sched-");
        this.taskScheduler.setPoolSize(2);
        this.taskScheduler.initialize();
    }

    @Override
    public synchronized void schedule(String jobCode, String cronExpression, Runnable task) {
        // 重复注册同一 jobCode 时先撤销旧触发，保证 cron 变更即时生效
        unschedule(jobCode);
        ScheduledFuture<?> future = taskScheduler.schedule(task, new CronTrigger(cronExpression));
        futures.put(jobCode, future);
        log.info("Job 已注册调度 jobCode={} cron={}", jobCode, cronExpression);
    }

    @Override
    public synchronized void unschedule(String jobCode) {
        ScheduledFuture<?> future = futures.remove(jobCode);
        if (future != null) {
            future.cancel(false);
            log.info("Job 已撤销调度 jobCode={}", jobCode);
        }
    }

    /** 关闭调度线程池，由容器在 bean 销毁时经 AutoCloseable 调用（接口式销毁，native AOT 无需按名反射）。 */
    @Override
    public void close() {
        taskScheduler.shutdown();
    }
}
