package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.job.api.dto.CreateJobCommand;
import com.sstlfsj.rule.job.api.dto.JobDefinitionDto;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;
import com.sstlfsj.rule.job.api.service.JobService;
import com.sstlfsj.rule.web.admin.dto.CreateJobRequest;
import com.sstlfsj.rule.web.admin.dto.CreateJobResponse;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Job 管理入口：创建 / 启用 / 禁用 / 查询 / 手动触发 / 运行记录（D11）。 */
@RestController
@RequestMapping("/admin/v1/jobs")
public class JobController {

    private final JobService jobService;
    /** Spring Boot 自动配置的 ObjectMapper bean，用于把请求中的 JSON 对象转为字符串。 */
    private final ObjectMapper objectMapper;

    public JobController(JobService jobService, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /admin/v1/jobs — 创建 Job。绑定 PULL Scene 时被拒绝（返回 400）。
     *
     * @param req     创建请求
     * @param actorId 操作人 ID（请求头 X-Actor-Id）
     * @return 新建 Job 主键
     */
    @PostMapping
    public ApiResponse<CreateJobResponse> createJob(
            @Valid @RequestBody CreateJobRequest req,
            @RequestHeader("X-Actor-Id") String actorId) {
        try {
            String subjectQueryJson = objectMapper.writeValueAsString(req.subjectQuery());
            String payloadTemplateJson = req.payloadTemplate() != null
                    ? objectMapper.writeValueAsString(req.payloadTemplate()) : null;
            Long id = jobService.createJob(new CreateJobCommand(
                    req.tenantId(), req.sceneCode(), req.code(), req.name(),
                    req.cronExpression(), subjectQueryJson, req.eventType(),
                    payloadTemplateJson, actorId));
            return ApiResponse.ok(new CreateJobResponse(id));
        } catch (JacksonException e) {
            // 请求对象 → JSON 序列化不应失败，属内部错误
            throw new IllegalStateException("JSON 序列化失败", e);
        }
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
