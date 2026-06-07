package com.sstlfsj.rule.job.xxl.internal;

import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Scheduler} 的 xxl-job 适配实现：把 task 闭包动态注册成 {@link IJobHandler}（名=jobCode），
 * 并把 job seed 到 admin（"有了不管"，由 {@link XxlJobAdminClient} 保证）。admin 远程触发该 handler →
 * 执行 task → 复用 JobRunner 整套，与内制完全一条路。
 *
 * <p>注销语义：xxl-job-core 的 handler registry 是 ConcurrentHashMap，无公开注销 API 且不接受 null value，
 * 故 {@link #unschedule} 以一个 no-op tombstone handler 覆盖原闭包；admin 侧 cron / 启停由控制台权威管理，
 * 不在此删除 admin job。
 */
public class XxlJobSchedulerAdapter implements Scheduler {

    private static final Logger log = LoggerFactory.getLogger(XxlJobSchedulerAdapter.class);

    /** 注销后覆盖用的空 handler（共享，无状态）。 */
    private static final IJobHandler NOOP = new IJobHandler() {
        @Override
        public void execute() {
            // 已注销，不执行
        }
    };

    private final XxlJobAdminClient adminClient;

    public XxlJobSchedulerAdapter(XxlJobAdminClient adminClient) {
        this.adminClient = adminClient;
    }

    @Override
    public synchronized void schedule(String jobCode, String cronExpression, Runnable task) {
        XxlJobExecutor.registryJobHandler(jobCode, new IJobHandler() {
            @Override
            public void execute() {
                task.run();
            }
        });
        long adminJobId = adminClient.ensureJobSeeded(jobCode, cronExpression);
        log.info("xxl-job 注册 handler={} adminJobId={} cron={}", jobCode, adminJobId, cronExpression);
    }

    @Override
    public synchronized void unschedule(String jobCode) {
        XxlJobExecutor.registryJobHandler(jobCode, NOOP);
        log.info("xxl-job 注销 handler={}（覆盖为 no-op）", jobCode);
    }
}
