package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 审计日志与评估会话查询入口。 */
@RestController
@RequestMapping("/admin/v1")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** GET /admin/v1/evaluation-sessions — 分页查询评估会话
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

    /** GET /admin/v1/evaluation-sessions/{sessionId}/trace — 查询评估节点 trace
     * @param sessionId 评估会话 ID @param tenantId 租户
     * @return 扁平节点 trace 列表，按 node_path 字典序 */
    @GetMapping("/evaluation-sessions/{sessionId}/trace")
    public ApiResponse<List<AuditService.TraceNodeEntry>> queryTrace(
            @PathVariable Long sessionId,
            @RequestParam String tenantId) {
        return ApiResponse.ok(auditService.queryTrace(tenantId, sessionId));
    }

    /** GET /admin/v1/evaluation-sessions/{sessionId}/trace/tree — 嵌套树格式（§6.2 完整契约） */
    @GetMapping("/evaluation-sessions/{sessionId}/trace/tree")
    public ApiResponse<List<AuditService.TraceTreeNode>> getTraceTree(
            @PathVariable Long sessionId,
            @RequestParam String tenantId) {
        return ApiResponse.ok(auditService.queryTraceTree(tenantId, sessionId));
    }

    /** GET /admin/v1/audit-logs — 分页查询操作审计日志
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

    /**
     * GET /admin/v1/rules/{ruleDefinitionId}/sessions — 按规则定义 ID 查询历史评估会话。
     *
     * @param ruleDefinitionId 规则定义 ID
     * @param status           可选状态过滤（HIT / MISS / ERROR / BLOCKED）
     * @param limit            每页条数，默认 20
     * @param offset           偏移量，默认 0
     * @return 分页历史评估会话列表
     */
    @GetMapping("/rules/{ruleDefinitionId}/sessions")
    public ApiResponse<AuditService.PageResult<AuditService.RuleSessionEntry>> querySessionsByRule(
            @PathVariable Long ruleDefinitionId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ApiResponse.ok(auditService.querySessionsByRuleDefinition(
                ruleDefinitionId, status, limit, offset));
    }
}
