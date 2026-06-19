package com.sstlfsj.rule.job.api.dto;

import java.time.Instant;

/**
 * 调度任务视图对象。
 *
 * @param id        主键
 * @param tenantId  租户 ID
 * @param code      任务编码
 * @param name      展示名
 * @param taskType  开放类型名（如 TRIGGER / OUTCOME_INGESTION）
 * @param cron      cron 表达式
 * @param config    配置（去中心化:框架核不持 typed 全集,以解析后的 JSON 对象出口）
 * @param status    状态（ACTIVE / DISABLED）
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ScheduledTaskVO(Long id, Long tenantId, String code, String name, String taskType,
                              String cron, Object config, String status, Instant createdAt, Instant updatedAt) {}
