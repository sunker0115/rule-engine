package com.sstlfsj.rule.job.internal.example;

import com.sstlfsj.rule.job.api.JobTarget;
import com.sstlfsj.rule.job.api.annotation.RuleJob;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 注解式 Job 演示（仅 {@code local} profile）：定时对「近期登录用户」跑欺诈检测。
 *
 * <p>启动期由 RuleJobScanner 自动落库为 BEAN_METHOD 类型 Job 并注册调度；
 * 手动触发：{@code POST /admin/v1/jobs/{id}/trigger?tenantId=1}。
 */
@Component
@Profile("local")
class DemoFraudJob {

    /**
     * 演示主体查询：返回近期登录用户。真实场景替换为查 login 日志表
     * （如注入 JdbcTemplate 查「10 分钟前登录的用户」），返回 {@link JobTarget} 列表，
     * 可经 {@code JobTarget.of(id, payload)} / {@code withProvidedMetrics} 携带预提供值。
     *
     * @return 目标列表
     */
    @RuleJob(code = "demo-daily", cron = "0 0 3 * * *", tenant = "1",
            scene = "fraud_check", eventType = "login", name = "演示每日欺诈扫描")
    public List<JobTarget> recentLoginUsers() {
        return List.of(JobTarget.of("user-001"), JobTarget.of("user-002"));
    }
}
