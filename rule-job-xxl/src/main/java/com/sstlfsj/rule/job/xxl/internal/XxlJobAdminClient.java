package com.sstlfsj.rule.job.xxl.internal;

/** xxl-job admin 接入 SPI：登录态管理 + job seed（"有了不管"语义由实现保证）。 */
public interface XxlJobAdminClient {

    /**
     * 确保 admin 侧存在匹配 jobDesc + executorHandler 的 jobinfo；不存在则新建，已存在则保持不动。
     *
     * @param jobDesc         job 描述（用于唯一定位，如 "task-42"）
     * @param executorHandler 执行器 handler 名（通用模式固定为 "scheduled-task-runner"）
     * @param cron            cron 表达式（仅新建时写入）
     * @param executorParam   执行器 param（通用模式传 taskId 字符串；旧 per-task 模式传 ""）
     * @return admin 侧该 job 的 id
     */
    long ensureJobSeeded(String jobDesc, String executorHandler, String cron, String executorParam);
}
