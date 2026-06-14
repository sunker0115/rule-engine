package com.sstlfsj.rule.audit.api.service;

import com.sstlfsj.rule.audit.api.dto.AuditLogQuery;
import com.sstlfsj.rule.audit.api.dto.EvalSessionQuery;

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
     * @param q 封装所有查询条件（tenantId / resourceType / resourceId / action / actorId / from / to / page / size），
     *          新增筛选字段只需改 AuditLogQuery record
     * @return 分页结果
     */
    PageResult<AuditLogEntry> queryAuditLogs(AuditLogQuery q);

    /** 评估会话条目，记录一次规则评估的基本信息。 */
    record EvalSessionEntry(
            String sessionId,
            String tenantId,
            String sceneCode,
            String eventId,
            String status,
            String finalDecision,
            Integer evalDurationMs,
            java.time.Instant startedAt,
            java.time.Instant finishedAt
    ) {}

    /**
     * 分页查询评估会话记录。
     *
     * @param q 封装所有查询条件（tenantId / sceneCode / status / eventId / page / size），
     *          新增筛选字段只需改 EvalSessionQuery record
     * @return 分页结果
     */
    PageResult<EvalSessionEntry> queryEvalSessions(EvalSessionQuery q);

    /** 节点 trace 条目，对应 node_trace 表一行。 */
    record TraceNodeEntry(
            String nodePath,
            String nodeType,
            String conditionType,
            String metricCode,
            String actualValue,
            Boolean result,
            String errorCode,
            String valueSource,
            String ruleCode,
            Long ruleVersion
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

    /** 嵌套树节点，与 §3.3 dry-run nodeTrace 格式一致。 */
    record TraceTreeNode(
            String nodeType,
            String conditionType,
            String metricCode,
            String actualValue,
            Boolean result,
            String errorCode,
            String valueSource,
            String ruleCode,
            Long ruleVersion,
            List<TraceTreeNode> children
    ) {}

    /**
     * 查询指定评估会话的节点 trace 树（嵌套，与 §3.3 dry-run nodeTrace 格式一致）。
     *
     * <p>内部按 node_path（点分数字字符串）重建 AST 树结构：最短路径为根，子路径挂到父路径节点。</p>
     *
     * @param tenantId  租户标识
     * @param sessionId 评估会话 ID
     * @return 根节点列表（正常 AST 只有一个根，Pre-Gate 阻断时可能为空）
     */
    List<TraceTreeNode> queryTraceTree(String tenantId, Long sessionId);

    /** 按规则定义查询历史评估会话的条目，含关联的规则版本号。 */
    record RuleSessionEntry(
            String sessionId,
            String eventId,
            String subjectId,
            String status,
            String finalDecision,
            Integer evalDurationMs,
            java.time.Instant startedAt,
            Long ruleVersionId
    ) {}

    /**
     * 按规则定义 ID 查询历史评估会话，支持 status 过滤和 limit/offset 分页。
     *
     * @param ruleDefinitionId 规则定义 ID
     * @param status           可选状态过滤（HIT / MISS / ERROR / BLOCKED），null 表示不过滤
     * @param limit            每页条数
     * @param offset           偏移量（从 0 开始）
     * @return 分页结果
     */
    PageResult<RuleSessionEntry> querySessionsByRuleDefinition(
            Long ruleDefinitionId, String status, int limit, int offset);

    /**
     * 查询评估会话所属场景编码（供 trace / replay 展示出口解析敏感集，D71）。
     *
     * @param tenantId  租户标识（数字字符串）
     * @param sessionId 评估会话 ID
     * @return 场景编码；会话不存在返回 null
     */
    String getSessionSceneCode(String tenantId, Long sessionId);

    /**
     * 查询单次评估会话详情。
     *
     * @param tenantId  租户标识
     * @param sessionId 评估会话 ID
     * @return 会话详情；不存在返回 null
     */
    EvalSessionEntry getSession(String tenantId, Long sessionId);
}
