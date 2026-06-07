package com.sstlfsj.rule.job.api.service;

import com.sstlfsj.rule.job.api.dto.CreateJobCommand;
import com.sstlfsj.rule.job.api.dto.JobDefinitionDto;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;

import java.util.List;

/** Job 生命周期管理：CRUD + 手动触发 + 执行记录查询（D11）。 */
public interface JobService {

    /**
     * 创建 Job。绑定的 Scene 为 PULL 时拒绝创建。
     *
     * @param cmd 创建入参
     * @return 新建 Job 主键
     */
    Long createJob(CreateJobCommand cmd);

    /**
     * 启用 Job 并注册到调度器。绑定的 Scene 为 PULL 时拒绝启用。
     *
     * @param tenantId 租户 ID
     * @param jobId    Job 主键
     */
    void enableJob(String tenantId, Long jobId);

    /**
     * 禁用 Job 并从调度器撤销。
     *
     * @param tenantId 租户 ID
     * @param jobId    Job 主键
     */
    void disableJob(String tenantId, Long jobId);

    /**
     * 查询租户全部 Job。
     *
     * @param tenantId 租户 ID
     * @return Job 列表
     */
    List<JobDefinitionDto> listJobs(String tenantId);

    /**
     * 查询单个 Job 详情。
     *
     * @param tenantId 租户 ID
     * @param jobId    Job 主键
     * @return Job 详情
     */
    JobDefinitionDto getJob(String tenantId, Long jobId);

    /**
     * 手动触发一次 Job（管理能力，不经调度器）。
     *
     * @param tenantId 租户 ID
     * @param jobId    Job 主键
     * @return 本次执行记录
     */
    JobExecutionVO triggerOnce(String tenantId, Long jobId);

    /**
     * 查询 Job 最近若干次执行记录。
     *
     * @param tenantId 租户 ID
     * @param jobId    Job 主键
     * @param limit    返回条数上限
     * @return 执行记录列表（按触发时间倒序）
     */
    List<JobExecutionVO> recentExecutions(String tenantId, Long jobId, int limit);
}
