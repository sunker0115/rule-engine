package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.job.api.dto.CreateScheduledTaskRequest;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskExecutionVO;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;
import com.sstlfsj.rule.job.api.service.ScheduledTaskService;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 调度任务管理入口：启用 / 禁用 / 查询 / 手动触发 / 运行记录。
 *
 * <p>TRIGGER 任务由 {@code @TriggerTask} 注解驱动、启动期自动落库。
 * OUTCOME_INGESTION 任务可经 {@code POST /admin/v1/scheduled-tasks} 动态创建（SQL-direct 源）。
 */
@RestController
@RequestMapping("/admin/v1/scheduled-tasks")
@RequiredArgsConstructor
public class ScheduledTaskController {

    private final ScheduledTaskService scheduledTaskService;

    /**
     * POST /admin/v1/scheduled-tasks — 创建 OUTCOME_INGESTION 调度任务（SQL-direct 源）。
     *
     * @param req 创建请求体
     * @return 201 Created + 任务 VO
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ScheduledTaskVO> create(@Valid @RequestBody CreateScheduledTaskRequest req) {
        return ApiResponse.ok(scheduledTaskService.create(req));
    }

    /**
     * GET /admin/v1/scheduled-tasks?tenantId=xxx — 查询租户全部任务。
     *
     * @param tenantId 租户 ID
     * @return 任务列表
     */
    @GetMapping
    public ApiResponse<List<ScheduledTaskVO>> list(@RequestParam Long tenantId) {
        return ApiResponse.ok(scheduledTaskService.list(tenantId));
    }

    /**
     * GET /admin/v1/scheduled-tasks/{id}?tenantId=xxx — 查询任务详情。
     *
     * @param id       任务主键
     * @param tenantId 租户 ID
     * @return 任务详情
     */
    @GetMapping("/{id}")
    public ApiResponse<ScheduledTaskVO> get(
            @PathVariable Long id, @RequestParam Long tenantId) {
        return ApiResponse.ok(scheduledTaskService.get(tenantId, id));
    }

    /**
     * POST /admin/v1/scheduled-tasks/{id}/enable?tenantId=xxx — 启用任务并注册调度。
     *
     * @param id       任务主键
     * @param tenantId 租户 ID
     */
    @PostMapping("/{id}/enable")
    public ApiResponse<Void> enable(
            @PathVariable Long id, @RequestParam Long tenantId) {
        scheduledTaskService.enable(tenantId, id);
        return ApiResponse.ok(null);
    }

    /**
     * POST /admin/v1/scheduled-tasks/{id}/disable?tenantId=xxx — 禁用任务并撤销调度。
     *
     * @param id       任务主键
     * @param tenantId 租户 ID
     */
    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disable(
            @PathVariable Long id, @RequestParam Long tenantId) {
        scheduledTaskService.disable(tenantId, id);
        return ApiResponse.ok(null);
    }

    /**
     * POST /admin/v1/scheduled-tasks/{id}/trigger?tenantId=xxx — 手动触发一次任务。
     *
     * @param id       任务主键
     * @param tenantId 租户 ID
     * @return 本次执行记录
     */
    @PostMapping("/{id}/trigger")
    public ApiResponse<ScheduledTaskExecutionVO> trigger(
            @PathVariable Long id, @RequestParam Long tenantId) {
        return ApiResponse.ok(scheduledTaskService.triggerOnce(tenantId, id));
    }

    /**
     * GET /admin/v1/scheduled-tasks/{id}/executions?tenantId=xxx&limit=20 — 查询最近执行记录。
     *
     * @param id       任务主键
     * @param tenantId 租户 ID
     * @param limit    返回条数上限（默认 20）
     * @return 执行记录列表（按触发时间倒序）
     */
    @GetMapping("/{id}/executions")
    public ApiResponse<List<ScheduledTaskExecutionVO>> recentExecutions(
            @PathVariable Long id, @RequestParam Long tenantId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(scheduledTaskService.recentExecutions(tenantId, id, limit));
    }

    /**
     * DELETE /admin/v1/scheduled-tasks/{id}?tenantId=xxx — 删除任务（撤销调度 + 删行）。
     * TRIGGER 任务删后下次启动会被 {@code @TriggerTask} 扫描重新 seed；OUTCOME_INGESTION 删后不恢复。
     *
     * @param id       任务主键
     * @param tenantId 租户 ID
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @RequestParam Long tenantId) {
        scheduledTaskService.delete(tenantId, id);
        return ApiResponse.ok(null);
    }
}
