package com.sstlfsj.rule.kernel.api.spi.task;

/**
 * 调度任务执行器 SPI（去中心化:各模块实现,只依赖 kernel;job-svc 经 Spring DI 收集）。
 *
 * @param <C> 该型自带的 config record（无共享基类）
 */
public interface TaskExecutor<C> {
    /** 开放类型名（如 "TRIGGER" / "OUTCOME_INGESTION"），与 scheduled_task.task_type 对应。 */
    String type();

    /** config 子类型,供 registry 把 config JSON 反序列化成 C。 */
    Class<C> configType();

    /**
     * 执行一次任务。
     *
     * @param ctx    运行上下文（taskRunId / taskId / tenantId / cursor）
     * @param config typed 配置（由 registry 从 config JSON 反序列化得到）
     * @return 执行结果（含推进后的 newCursor）
     */
    TaskRunResult execute(TaskRunContext ctx, C config);
}
