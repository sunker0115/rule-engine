package com.sstlfsj.rule.job.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 触发任务（TriggerTask）：标在 Spring Bean 的「主体查询方法」上（D11 / D47）。
 *
 * <p>被标注的方法即该任务的目标来源，两种签名二选一（{@code SubjectTarget.subjectId} 为 subjectId，
 * {@code payload} / {@code providedMetrics} 随合成事件透传）：
 * <ul>
 *   <li>小数据量：无参、返回 {@code List<SubjectTarget>}；</li>
 *   <li>大数据量：单 {@code SubjectPage} 参、返回 {@code List<SubjectTarget>} —— 分页拉取（仿 ElasticJob DataflowJob），
 *       框架 page 0、1、2… 反复调用拉到空批为止，每批只占一页内存。方法体用 {@code page.offset()} /
 *       {@code page.pageSize()} 作 SQL {@code LIMIT ... OFFSET ...}。</li>
 * </ul>
 *
 * <p>启动期由 ScheduledTaskScanner 自动 upsert 到 {@code scheduled_task}（TRIGGER 型，
 * typed {@code TriggerConfig{sceneCode,eventType,BeanMethodQuery(ref)}}）并注册到调度器，
 * 统一经 {@code /admin/v1/scheduled-tasks} 管理。触发时反射调用本方法查主体 → 合成 RuleEvent → 注入标准评估链路。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TriggerTask {

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
