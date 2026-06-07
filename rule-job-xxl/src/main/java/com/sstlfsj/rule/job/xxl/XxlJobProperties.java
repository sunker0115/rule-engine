package com.sstlfsj.rule.job.xxl;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xxl-job 适配器配置：executor 注册参数 + admin 接入凭证。
 *
 * <p>敏感字段（accessToken / adminPassword）按 secret 处理，yml 用 ${XXL_ACCESS_TOKEN} 等占位符经环境注入，
 * 不入库、不硬编码。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.job.xxl")
public class XxlJobProperties {

    /** admin 根地址（含 context-path，逗号分隔多实例），如 http://127.0.0.1:8080/xxl-job-admin。 */
    private String adminAddresses;

    /** 执行器 appname，对应 admin 侧 jobgroup 的 appname。 */
    private String appname = "rule-engine";

    /** 执行器对外注册地址（空则由 ip:port 拼）。 */
    private String address;

    /** 执行器 ip（空则自动探测）。 */
    private String ip;

    /** 执行器回调端口（Netty EmbedServer 监听；<=0 时从 9999 起找可用口）。 */
    private int port = 9999;

    /** admin / executor 通信令牌（敏感，经 ${XXL_ACCESS_TOKEN} 注入）。 */
    private String accessToken;

    /** 执行器本地日志目录。 */
    private String logPath = "/data/applogs/xxl-job/jobhandler";

    /** 执行器日志保留天数。 */
    private int logRetentionDays = 30;

    /** seed job 到 admin 所需的登录账号（敏感，需 ADMIN_ROLE）。 */
    private String adminUsername;

    /** seed job 到 admin 所需的登录密码（敏感，经 ${XXL_ADMIN_PASSWORD} 注入）。 */
    private String adminPassword;

    /** 是否启动执行器（false 则 XxlJobExecutor.start 跳过，不绑端口、不注册 admin；测试 / 仅装配场景用）。 */
    private boolean enabled = true;
}
