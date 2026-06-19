package com.sstlfsj.rule.job.api;

/**
 * 调度任务执行器 SPI。每型一个实现,Spring 自动收集,按 type() 路由。
 *
 * @param <C> 该型的 TaskConfig 子类型
 */
public interface TaskExecutor<C extends TaskConfig> {
    /** 该 executor 负责的任务类型。 */
    TaskType type();
    /** config 子类型,供 dispatcher 反序列化校验。 */
    Class<C> configType();
    /**
     * 执行一次任务。
     * @param ctx    运行上下文（taskRunId / taskId / tenantId）
     * @param config typed 配置
     * @return 执行结果
     */
    TaskRunResult execute(TaskRunContext ctx, C config);
}
