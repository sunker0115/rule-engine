package com.sstlfsj.rule.job.xxl.internal;

import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.sstlfsj.rule.kernel.api.spi.scheduler.TaskRunCallback;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * {@link Scheduler} 的 xxl-job 适配实现。
 *
 * <p><b>通用 handler 模式</b>：启动时注册唯一 {@value #UNIVERSAL_HANDLER}，
 * 每个任务的 cron 触发由 admin 带 executorParam=taskId 派发；handler 根据 param
 * 查本地 runnables 执行，缺失则降级调 {@link TaskRunCallback}（如新建任务仅在
 * API 实例注册，派到其他实例时走回调从 DB 重载）。
 *
 * <p>此设计使全集群共享同一 handler name，新任务无需各实例重启即可调度。
 */
public class XxlJobSchedulerAdapter implements Scheduler {

    /** 全集群共用的通用 handler 名——所有实例注册同一名称。 */
    public static final String UNIVERSAL_HANDLER = "scheduled-task-runner";

    /** 广播 handler 名（独立于单派发 UNIVERSAL_HANDLER，param 是业务 payload 非 taskId）。 */
    public static final String BROADCAST_HANDLER = "config-broadcast-runner";

    private static final Logger log = LoggerFactory.getLogger(XxlJobSchedulerAdapter.class);

    private final XxlJobAdminClient adminClient;
    private final ObjectProvider<TaskRunCallback> callbackProvider;

    /** taskId → Runnable，用于本实例已注册的任务快速执行。 */
    private final Map<Long, Runnable> runnables = new ConcurrentHashMap<>();

    /** 广播 jobinfo 的 admin id（惰性 seed，首次 triggerBroadcast 时完成，触后复用）。 */
    private volatile long broadcastJobId = -1;

    /** code → 广播处理器。 */
    private final Map<String, Consumer<String>> broadcastConsumers = new ConcurrentHashMap<>();

    /**
     * @param adminClient      admin 接入客户端
     * @param callbackProvider TaskRunCallback 惰性 provider（惰性解析断构造期 bean 循环依赖）
     */
    public XxlJobSchedulerAdapter(XxlJobAdminClient adminClient, ObjectProvider<TaskRunCallback> callbackProvider) {
        this.adminClient = adminClient;
        this.callbackProvider = callbackProvider;
        // 注册通用 handler（一次即可，各实例相同 name）
        XxlJobExecutor.registryJobHandler(UNIVERSAL_HANDLER, new IJobHandler() {
            @Override
            public void execute() {
                String param = XxlJobHelper.getJobParam();
                if (param == null || param.isBlank()) {
                    log.warn("scheduled-task-runner: 缺少 taskId param，跳过");
                    return;
                }
                long taskId;
                try {
                    taskId = Long.parseLong(param.trim());
                } catch (NumberFormatException e) {
                    log.warn("scheduled-task-runner: param 非 taskId='{}'，跳过", param);
                    return;
                }
                Runnable r = runnables.get(taskId);
                if (r != null) {
                    r.run();
                } else {
                    // 降级：本实例未缓存该任务（如 API 创建后其他实例尚未 register）
                    // ObjectProvider 惰性解析，不形成构造期 bean 循环依赖
                    log.debug("scheduled-task-runner: taskId={} 本实例无缓存，降级调 callback", taskId);
                    callbackProvider.getObject().run(taskId);
                }
            }
        });
        // 注册广播 handler（独立 name，param 是业务 payload 非 taskId）
        XxlJobExecutor.registryJobHandler(BROADCAST_HANDLER, new IJobHandler() {
            @Override
            public void execute() {
                String param = XxlJobHelper.getJobParam();
                if (param == null || param.isBlank()) {
                    log.warn("config-broadcast-runner: 缺少 param，跳过");
                    return;
                }
                Consumer<String> consumer = broadcastConsumers.get("config-change");
                if (consumer != null) {
                    consumer.accept(param);
                } else {
                    log.debug("config-broadcast-runner: 本实例未注册 config-change handler，跳过 param={}", param);
                }
            }
        });
        log.info("xxl-job 通用 handler '{}' 注册完成", UNIVERSAL_HANDLER);
    }

    @Override
    public synchronized void schedule(String jobCode, String cronExpression, Runnable task) {
        long taskId = parseTaskId(jobCode);
        runnables.put(taskId, task);
        long adminJobId = adminClient.ensureJobSeeded(
                "task-" + taskId,       // jobDesc
                UNIVERSAL_HANDLER,      // executorHandler
                cronExpression,
                "FIRST",                // routeStrategy: cron 单派发
                String.valueOf(taskId)  // executorParam
        );
        log.info("xxl-job 注册 taskId={} adminJobId={} cron={}", taskId, adminJobId, cronExpression);
    }

    @Override
    public synchronized void unschedule(String jobCode) {
        long taskId = parseTaskId(jobCode);
        runnables.remove(taskId);
        // XXL admin job 保留（运维在控制台管停用），只清本地 runnable 缓存
        log.info("xxl-job 注销 taskId={} (runnable 缓存已移除，admin job 保留)", taskId);
    }

    @Override
    public void scheduleBroadcast(String code, Consumer<String> onEachNode) {
        broadcastConsumers.put(code, onEachNode);
        log.info("xxl-job 广播 handler 已注册 code={}", code);
    }

    @Override
    public void triggerBroadcast(String code, String param) {
        adminClient.triggerJob(ensureBroadcastJobSeeded(), param);
    }

    /** 惰性 seed 广播 jobinfo（首次 triggerBroadcast 时完成，避免构造期网络 I/O）。 */
    private long ensureBroadcastJobSeeded() {
        long id = this.broadcastJobId;
        if (id > 0) return id;
        synchronized (this) {
            id = this.broadcastJobId;
            if (id > 0) return id;
            id = adminClient.ensureJobSeeded(
                    "config-broadcast", BROADCAST_HANDLER, "0 0 0 1 1 ?", "SHARDING_BROADCAST", "");
            this.broadcastJobId = id;
            log.info("xxl-job 广播 handler '{}' seed 完成 broadcastJobId={}", BROADCAST_HANDLER, id);
            return id;
        }
    }

    /** 从 jobCode("scheduled-task:42") 解析 taskId。 */
    private static long parseTaskId(String jobCode) {
        final String PREFIX = "scheduled-task:";
        if (!jobCode.startsWith(PREFIX)) {
            throw new IllegalArgumentException("jobCode 格式非法: " + jobCode);
        }
        return Long.parseLong(jobCode.substring(PREFIX.length()));
    }
}
