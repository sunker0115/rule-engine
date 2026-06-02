package com.sstlfsj.rule.audit.api.service;

import java.util.List;

/** 提供审计日志和评估会话的查询能力。 */
public interface AuditService {

    /** 审计日志条目，记录资源变更的操作历史。 */
    record AuditLogEntry(
            Long id,
            String tenantId,
            String resourceType,
            Long resourceId,
            String action,
            String actorId,
            String actorType,
            String beforeSnapshot,
            String afterSnapshot,
            java.time.Instant occurredAt
    ) {}

    /** 分页结果包装。 */
    record PageResult<T>(List<T> items, long total, int page, int size) {}

    /**
     * 分页查询审计日志。
     *
     * @param tenantId     租户标识
     * @param resourceType 资源类型（如 SCENE、RULE_DEFINITION）
     * @param resourceId   资源 ID，null 表示不过滤
     * @param page         页码（从 0 开始）
     * @param size         每页条数
     * @return 分页结果
     */
    PageResult<AuditLogEntry> queryAuditLogs(String tenantId, String resourceType,
                                              Long resourceId, int page, int size);

    /** 评估会话条目，记录一次规则评估的基本信息。 */
    record EvalSessionEntry(
            String sessionId,
            String tenantId,
            String sceneCode,
            String eventId,
            String status,
            java.time.Instant startedAt
    ) {}

    /**
     * 分页查询评估会话记录。
     *
     * @param tenantId 租户标识
     * @param eventId  事件 ID，null 表示不过滤
     * @param page     页码（从 0 开始）
     * @param size     每页条数
     * @return 分页结果
     */
    PageResult<EvalSessionEntry> queryEvalSessions(String tenantId, String eventId,
                                                    int page, int size);
}
