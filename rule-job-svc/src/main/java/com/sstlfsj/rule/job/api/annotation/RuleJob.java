package com.sstlfsj.rule.job.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注解式定时 Job：标在 Spring Bean 的「主体查询方法」上（D11 / D47）。
 *
 * <p>被标注的方法即该 Job 的目标来源，须无参、返回 {@code List<JobTarget>}
 * （{@code JobTarget.subjectId} 为 subjectId，{@code payload} / {@code providedMetrics} 随合成事件透传）。
 *
 * <p>启动期由 RuleJobScanner 自动 upsert 到 {@code job_definition}
 * （{@code subject_query = {"type":"BEAN_METHOD","ref":"<bean>#<method>"}}）并注册到调度器，
 * 统一经 {@code /admin/v1/jobs} 管理。触发时反射调用本方法查主体 → 合成 RuleEvent → 注入标准评估链路。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RuleJob {

    /** Job 编码，租户 + 场景内唯一。 */
    String code();

    /** Spring 6 段 cron（秒 分 时 日 月 周）。 */
    String cron();

    /** 所属租户 ID。 */
    String tenant();

    /** 绑定的 Scene code（须为 PUSH / HYBRID）。 */
    String scene();

    /** 合成 RuleEvent 使用的 eventType。 */
    String eventType();

    /** Job 展示名称，缺省用 code。 */
    String name() default "";
}
