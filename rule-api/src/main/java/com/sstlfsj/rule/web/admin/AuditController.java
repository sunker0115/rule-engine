package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.audit.api.dto.AuditLogQuery;
import com.sstlfsj.rule.audit.api.dto.EvalSessionQuery;
import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.common.PageResponse;
import com.sstlfsj.rule.web.mask.SensitiveRefsResolver;
import com.sstlfsj.rule.web.mask.TraceMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;

/** 审计日志与评估会话查询入口。 */
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;
    private final SensitiveRefsResolver sensitiveRefsResolver;

    /** GET /admin/v1/evaluation-sessions — 分页查询评估会话
     * @param tenantId 租户 @param eventId 可选过滤 @param page 页码 @param size 每页大小
     * @return 分页评估会话列表 */
    @GetMapping("/evaluation-sessions")
    public ApiResponse<PageResponse<AuditService.EvalSessionEntry>> querySessions(
            @RequestParam Long tenantId,
            @RequestParam(required = false) String sceneCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuditService.PageResult<AuditService.EvalSessionEntry> result =
                auditService.queryEvalSessions(new EvalSessionQuery(tenantId, sceneCode, status, eventId, page - 1, size));
        return ApiResponse.ok(PageResponse.of(result.items(), result.total(), page, size));
    }

    /** GET /admin/v1/evaluation-sessions/{sessionId} — 查询单次评估会话详情
     * @param sessionId 评估会话 ID @param tenantId 租户
     * @return 会话详情；不存在返回 404 */
    @GetMapping("/evaluation-sessions/{sessionId}")
    public ResponseEntity<ApiResponse<AuditService.EvalSessionEntry>> getSession(
            @PathVariable Long sessionId,
            @RequestParam Long tenantId) {
        AuditService.EvalSessionEntry session = auditService.getSession(tenantId, sessionId);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND",
                            "会话不存在: sessionId=" + sessionId + ", tenantId=" + tenantId));
        }
        return ResponseEntity.ok(ApiResponse.ok(session));
    }

    /** GET /admin/v1/evaluation-sessions/{sessionId}/trace — 查询评估节点 trace
     * @param sessionId 评估会话 ID @param tenantId 租户
     * @return 扁平节点 trace 列表，按 node_path 字典序 */
    @GetMapping("/evaluation-sessions/{sessionId}/trace")
    public ApiResponse<List<AuditService.TraceNodeEntry>> queryTrace(
            @PathVariable Long sessionId,
            @RequestParam Long tenantId) {
        List<AuditService.TraceNodeEntry> trace = auditService.queryTrace(tenantId, sessionId);
        return ApiResponse.ok(TraceMasker.maskFlat(sensitiveRefsResolver.forSession(tenantId, sessionId), trace));
    }

    /** GET /admin/v1/evaluation-sessions/{sessionId}/trace/tree — 嵌套树格式（§6.2 完整契约） */
    @GetMapping("/evaluation-sessions/{sessionId}/trace/tree")
    public ApiResponse<List<AuditService.TraceTreeNode>> getTraceTree(
            @PathVariable Long sessionId,
            @RequestParam Long tenantId) {
        List<AuditService.TraceTreeNode> tree = auditService.queryTraceTree(tenantId, sessionId);
        return ApiResponse.ok(TraceMasker.maskTree(sensitiveRefsResolver.forSession(tenantId, sessionId), tree));
    }

    /** GET /admin/v1/audit-logs — 分页查询操作审计日志，支持多条件筛选
     * @param tenantId 租户 @param resourceType 可选资源类型 @param resourceId 可选资源 ID
     * @param action 可选操作类型 @param actorId 可选操作人 @param from 起始时间 @param to 结束时间
     * @param page 页码 @param size 每页大小
     * @return 分页审计日志列表 */
    @GetMapping("/audit-logs")
    public ApiResponse<PageResponse<AuditService.AuditLogEntry>> queryAuditLogs(
            @RequestParam Long tenantId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuditService.PageResult<AuditService.AuditLogEntry> result =
                auditService.queryAuditLogs(new AuditLogQuery(tenantId, resourceType, resourceId, action, actorId, from, to, page - 1, size));
        return ApiResponse.ok(PageResponse.of(result.items(), result.total(), page, size));
    }

    /**
     * GET /admin/v1/rules/{ruleDefinitionId}/sessions — 按规则定义 ID 查询历史评估会话。
     *
     * @param ruleDefinitionId 规则定义 ID
     * @param status           可选状态过滤（HIT / MISS / ERROR / BLOCKED）
     * @param page             页码，默认 1
     * @param size             每页条数，默认 20
     * @return 分页历史评估会话列表
     */
    @GetMapping("/rules/{ruleDefinitionId}/sessions")
    public ApiResponse<PageResponse<AuditService.RuleSessionEntry>> querySessionsByRule(
            @PathVariable Long ruleDefinitionId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuditService.PageResult<AuditService.RuleSessionEntry> result =
                auditService.querySessionsByRuleDefinition(ruleDefinitionId, status, size, (page - 1) * size);
        return ApiResponse.ok(PageResponse.of(result.items(), result.total(), page, size));
    }
}
