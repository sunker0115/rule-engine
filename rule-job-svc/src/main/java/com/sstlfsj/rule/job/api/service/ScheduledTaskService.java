package com.sstlfsj.rule.job.api.service;

import com.sstlfsj.rule.job.api.dto.CreateScheduledTaskRequest;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskExecutionVO;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;

import java.util.List;

/** 调度任务管理服务：启用 / 禁用 / 查询 / 手动触发 / 执行记录（定义走 {@code @TriggerTask} 注解自动落库）。 */
public interface ScheduledTaskService {

    /**
     * 启用任务并注册到调度器。
     *
     * @param tenantId 租户 ID
     * @param taskId   任务主键
     */
    void enable(Long tenantId, Long taskId);

    /**
     * 禁用任务并撤销调度。
     *
     * @param tenantId 租户 ID
     * @param taskId   任务主键
     */
    void disable(Long tenantId, Long taskId);

    /**
     * 查询租户全部任务。
     *
     * @param tenantId 租户 ID
     * @return 任务列表
     */
    List<ScheduledTaskVO> list(Long tenantId);

    /**
     * 查询任务详情。
     *
     * @param tenantId 租户 ID
     * @param taskId   任务主键
     * @return 任务详情
     */
    ScheduledTaskVO get(Long tenantId, Long taskId);

    /**
     * 手动触发一次。
     *
     * @param tenantId 租户 ID
     * @param taskId   任务主键
     * @return 本次执行记录
     */
    ScheduledTaskExecutionVO triggerOnce(Long tenantId, Long taskId);

    /**
     * 查询最近执行记录（按触发时间倒序）。
     *
     * @param tenantId 租户 ID
     * @param taskId   任务主键
     * @param limit    返回条数上限
     * @return 执行记录列表
     */
    List<ScheduledTaskExecutionVO> recentExecutions(Long tenantId, Long taskId, int limit);

    /**
     * 创建 OUTCOME_INGESTION 调度任务（SQL-direct 源），并立即注册到调度器。
     * TRIGGER 任务由 {@code @TriggerTask} 注解 seed，不走此接口。
     *
     * @param req 创建请求
     * @return 创建后的任务 VO
     * @throws IllegalArgumentException 若 code 在该租户下已存在
     */
    ScheduledTaskVO create(CreateScheduledTaskRequest req);

    /**
     * 删除调度任务：从调度器撤销 + 删除 scheduled_task 行。
     * TRIGGER 任务删后会在下次启动被 {@code @TriggerTask} 扫描重新 seed；OUTCOME_INGESTION 删后不再恢复。
     *
     * @param tenantId 租户 ID
     * @param taskId   任务主键
     */
    void delete(Long tenantId, Long taskId);
}
