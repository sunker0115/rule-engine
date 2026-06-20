package com.sstlfsj.rule.job.xxl.internal;

/** xxl-job admin 接入 SPI：登录态管理 + job seed（"有了不管"语义由实现保证）。 */
public interface XxlJobAdminClient {

    /**
     * 确保 admin 侧存在匹配 jobDesc 的 jobinfo；不存在则按给定路由策略新建，已存在保持不动。
     *
     * @param jobDesc         job 描述（唯一定位，如 "task-42" / "config-broadcast"）
     * @param executorHandler 执行器 handler 名
     * @param cron            cron 表达式（仅新建时写入）
     * @param routeStrategy   路由策略（"FIRST" 单派发 / "SHARDING_BROADCAST" 广播）
     * @param executorParam   执行器 param
     * @return admin 侧该 job 的 id
     */
    long ensureJobSeeded(String jobDesc, String executorHandler, String cron,
                         String routeStrategy, String executorParam);

    /**
     * 触发现有 jobinfo（路由策略由 jobinfo 自身决定），覆盖 executorParam。
     *
     * @param adminJobId    admin 侧 jobinfo id
     * @param executorParam 本次触发的执行器 param
     */
    void triggerJob(long adminJobId, String executorParam);
}
