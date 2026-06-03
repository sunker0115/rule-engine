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

    /** 节点 trace 条目，对应 node_trace 表一行。 */
    record TraceNodeEntry(
            String nodePath,
            String nodeType,
            String conditionType,
            String metricCode,
            String actualValue,
            Boolean result,
            String errorCode,
            String valueSource
    ) {}

    /**
     * 查询指定评估会话的节点 trace 列表（扁平，按 node_path 字典序排列）。
     *
     * <p>注意：node_path 格式为点分数字字符串（如 "0.1.10"），VARCHAR 字典序在节点数
     * 超过 9 时与数字顺序不同（"0.1.10" 排在 "0.1.2" 之前）。v1 返回扁平列表，
     * 树重建留 §2.21 演进处理。</p>
     *
     * @param tenantId  租户标识（需为数字字符串，非数字将抛 NumberFormatException）
     * @param sessionId 评估会话 ID
     * @return 节点 trace 列表（无分页，单次 session 通常 < 200 行）
     */
    List<TraceNodeEntry> queryTrace(String tenantId, Long sessionId);
}
