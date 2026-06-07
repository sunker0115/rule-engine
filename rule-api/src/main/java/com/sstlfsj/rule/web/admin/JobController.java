package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.job.api.dto.JobDefinitionDto;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;
import com.sstlfsj.rule.job.api.service.JobService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Job 管理入口：启用 / 禁用 / 查询 / 手动触发 / 运行记录（D11 / D48）。
 *
 * <p>Job 定义由 {@code @RuleJob} 注解驱动、启动期自动落库，故无创建接口；本控制器只做管理。
 */
@RestController
@RequestMapping("/admin/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * GET /admin/v1/jobs?tenantId=xxx — 查询租户全部 Job。
     *
     * @param tenantId 租户 ID
     * @return Job 列表
     */
    @GetMapping
    public ApiResponse<List<JobDefinitionDto>> listJobs(@RequestParam String tenantId) {
        return ApiResponse.ok(jobService.listJobs(tenantId));
    }

    /**
     * GET /admin/v1/jobs/{id}?tenantId=xxx — 查询 Job 详情。
     *
     * @param id       Job 主键
     * @param tenantId 租户 ID
     * @return Job 详情
     */
    @GetMapping("/{id}")
    public ApiResponse<JobDefinitionDto> getJob(
            @PathVariable Long id, @RequestParam String tenantId) {
        return ApiResponse.ok(jobService.getJob(tenantId, id));
    }

    /**
     * POST /admin/v1/jobs/{id}/enable?tenantId=xxx — 启用 Job 并注册调度。
     *
     * @param id       Job 主键
     * @param tenantId 租户 ID
     */
    @PostMapping("/{id}/enable")
    public ApiResponse<Void> enableJob(
            @PathVariable Long id, @RequestParam String tenantId) {
        jobService.enableJob(tenantId, id);
        return ApiResponse.ok(null);
    }

    /**
     * POST /admin/v1/jobs/{id}/disable?tenantId=xxx — 禁用 Job 并撤销调度。
     *
     * @param id       Job 主键
     * @param tenantId 租户 ID
     */
    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disableJob(
            @PathVariable Long id, @RequestParam String tenantId) {
        jobService.disableJob(tenantId, id);
        return ApiResponse.ok(null);
    }

    /**
     * POST /admin/v1/jobs/{id}/trigger?tenantId=xxx — 手动触发一次 Job。
     *
     * @param id       Job 主键
     * @param tenantId 租户 ID
     * @return 本次执行记录
     */
    @PostMapping("/{id}/trigger")
    public ApiResponse<JobExecutionVO> triggerJob(
            @PathVariable Long id, @RequestParam String tenantId) {
        return ApiResponse.ok(jobService.triggerOnce(tenantId, id));
    }

    /**
     * GET /admin/v1/jobs/{id}/executions?tenantId=xxx&limit=20 — 查询最近执行记录。
     *
     * @param id       Job 主键
     * @param tenantId 租户 ID
     * @param limit    返回条数上限（默认 20）
     * @return 执行记录列表（按触发时间倒序）
     */
    @GetMapping("/{id}/executions")
    public ApiResponse<List<JobExecutionVO>> recentExecutions(
            @PathVariable Long id, @RequestParam String tenantId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(jobService.recentExecutions(tenantId, id, limit));
    }
}
