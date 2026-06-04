package com.sstlfsj.rule.web.audit;

import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 审计日志与评估会话查询入口。 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** GET /api/v1/evaluation-sessions — 分页查询评估会话
     * @param tenantId 租户 @param eventId 可选过滤 @param page 页码 @param size 每页大小
     * @return 分页评估会话列表 */
    @GetMapping("/evaluation-sessions")
    public ApiResponse<AuditService.PageResult<AuditService.EvalSessionEntry>> querySessions(
            @RequestParam String tenantId,
            @RequestParam(required = false) String eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.queryEvalSessions(tenantId, eventId, page, size));
    }

    /** GET /api/v1/evaluation-sessions/{sessionId}/trace — 查询评估节点 trace
     * @param sessionId 评估会话 ID @param tenantId 租户
     * @return 扁平节点 trace 列表，按 node_path 字典序 */
    @GetMapping("/evaluation-sessions/{sessionId}/trace")
    public ApiResponse<List<AuditService.TraceNodeEntry>> queryTrace(
            @PathVariable Long sessionId,
            @RequestParam String tenantId) {
        return ApiResponse.ok(auditService.queryTrace(tenantId, sessionId));
    }

    /** GET /api/v1/audit-logs — 分页查询操作审计日志
     * @param tenantId 租户 @param resourceType 可选资源类型 @param resourceId 可选资源 ID
     * @param page 页码 @param size 每页大小
     * @return 分页审计日志列表 */
    @GetMapping("/audit-logs")
    public ApiResponse<AuditService.PageResult<AuditService.AuditLogEntry>> queryAuditLogs(
            @RequestParam String tenantId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.queryAuditLogs(tenantId, resourceType, resourceId, page, size));
    }
}
