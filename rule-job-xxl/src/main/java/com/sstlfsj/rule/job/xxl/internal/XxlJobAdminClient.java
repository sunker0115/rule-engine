package com.sstlfsj.rule.job.xxl.internal;

/** xxl-job admin 接入 SPI：登录态管理 + job seed（"有了不管"语义由实现保证）。 */
public interface XxlJobAdminClient {

    /**
     * 确保 admin 侧存在 executorHandler=handlerName 的 jobinfo：不存在则按 cron 新建，已存在则保持不动
     * （admin 控制台为 cron 权威源，不覆盖运维改动）。
     *
     * @param handlerName 执行器 handler 名（= jobCode）
     * @param cron        cron 表达式（仅新建时写入）
     * @return admin 侧该 job 的 id
     */
    long ensureJobSeeded(String handlerName, String cron);
}
