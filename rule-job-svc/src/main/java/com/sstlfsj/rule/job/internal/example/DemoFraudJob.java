package com.sstlfsj.rule.job.internal.example;

import com.sstlfsj.rule.job.api.SubjectPage;
import com.sstlfsj.rule.job.api.SubjectTarget;
import com.sstlfsj.rule.job.api.annotation.TriggerTask;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 注解式 Job 演示（仅 {@code local} profile）：定时对「近期登录用户」跑欺诈检测。
 *
 * <p>启动期由 ScheduledTaskScanner 自动落库为 TRIGGER 型 scheduled_task 并注册调度；
 * 手动触发：{@code POST /admin/v1/scheduled-tasks/{id}/trigger?tenantId=1}。
 */
@Component
@Profile("local")
class DemoFraudJob {

    /**
     * 演示主体查询：返回近期登录用户。真实场景替换为查 login 日志表
     * （如注入 JdbcTemplate 查「10 分钟前登录的用户」），返回 {@link SubjectTarget} 列表，
     * 可经 {@code SubjectTarget.of(id, payload)} / {@code withProvidedMetrics} 携带预提供值。
     *
     * @return 目标列表
     */
    @TriggerTask(code = "demo-daily", cron = "0 0 3 * * *", tenant = "9100",
            scene = "fraud_check", eventType = "login", name = "演示每日欺诈扫描")
    public List<SubjectTarget> recentLoginUsers() {
        return List.of(SubjectTarget.of("user-001"), SubjectTarget.of("user-002"));
    }

    /**
     * 演示大数据量分页主体查询（仿 ElasticJob DataflowJob）：框架从 page 0 起反复调用，
     * 拉到空批为止。真实场景方法体用 {@code page.offset()} / {@code page.pageSize()} 作 SQL
     * {@code LIMIT ... OFFSET ...}；此处用 3 页假数据演示，page≥3 返空批停止。
     *
     * @param page 分页上下文（框架注入）
     * @return 当前页目标列表，空列表表示已无更多页
     */
    @TriggerTask(code = "demo-paged", cron = "0 30 3 * * *", tenant = "9100",
            scene = "fraud_check", eventType = "login", name = "演示分页欺诈扫描")
    public List<SubjectTarget> recentLoginUsersPaged(SubjectPage page) {
        if (page.pageNumber() >= 3) {
            return List.of();   // 空批 → 框架停止翻页
        }
        // 演示用每页 2 条（真实场景：login 表 SQL LIMIT page.pageSize() OFFSET page.offset()）
        return List.of(
                SubjectTarget.of("paged-user-" + page.pageNumber() + "-0"),
                SubjectTarget.of("paged-user-" + page.pageNumber() + "-1"));
    }
}
