package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.job.api.dto.ScheduledTaskExecutionVO;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;
import com.sstlfsj.rule.job.api.service.ScheduledTaskService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 调度任务管理入口：启用 / 禁用 / 查询 / 手动触发 / 运行记录。
 *
 * <p>任务定义由 {@code @TriggerTask} 注解驱动、启动期自动落库，故无创建接口；本控制器只做管理。
 */
@RestController
@RequestMapping("/admin/v1/scheduled-tasks")
@RequiredArgsConstructor
public class ScheduledTaskController {

    private final ScheduledTaskService scheduledTaskService;

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
}
