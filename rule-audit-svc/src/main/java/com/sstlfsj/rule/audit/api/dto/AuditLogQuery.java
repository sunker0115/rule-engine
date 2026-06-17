package com.sstlfsj.rule.audit.api.dto;

/**
 * 审计日志查询条件。
 *
 * @param tenantId     租户 ID（必填）
 * @param resourceType 资源类型（选填）
 * @param resourceId   资源 ID（选填）
 * @param action       操作类型（选填）
 * @param actorId      操作人（选填）
 * @param from         起始时间（选填，ISO 日期）
 * @param to           截止时间（选填，ISO 日期）
 * @param page         页码（0-based，与 MyBatis-Plus 对齐）
 * @param size         每页条数
 */
public record AuditLogQuery(
        Long tenantId,
        String resourceType,
        Long resourceId,
        String action,
        String actorId,
        String from,
        String to,
        int page,
        int size
) {}
