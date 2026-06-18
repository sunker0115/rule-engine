package com.sstlfsj.rule.audit.api.dto;

/**
 * 评估会话查询条件。
 *
 * @param tenantId  租户 ID（必填）
 * @param sceneCode 场景编码（选填）
 * @param status    会话状态（选填）
 * @param eventId   事件 ID（选填）
 * @param page      页码（0-based，与 MyBatis-Plus 对齐）
 * @param size      每页条数
 */
public record EvalSessionQuery(
        Long tenantId,
        String sceneCode,
        String status,
        String eventId,
        int page,
        int size
) {}
