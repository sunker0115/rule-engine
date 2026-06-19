package com.sstlfsj.rule.job.api.dto;

import com.sstlfsj.rule.job.api.TaskConfig;
import com.sstlfsj.rule.job.api.TaskType;

import java.time.Instant;

/**
 * 调度任务视图对象。
 *
 * @param id        主键
 * @param tenantId  租户 ID
 * @param code      任务编码
 * @param name      展示名
 * @param taskType  任务类型
 * @param cron      cron 表达式
 * @param config    typed 配置
 * @param status    状态（ACTIVE / DISABLED）
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ScheduledTaskVO(Long id, Long tenantId, String code, String name, TaskType taskType,
                              String cron, TaskConfig config, String status, Instant createdAt, Instant updatedAt) {}
