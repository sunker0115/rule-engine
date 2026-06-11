package com.sstlfsj.rule.job.api.dto;

import com.sstlfsj.rule.job.api.SubjectQuery;

/**
 * Job 定义响应 DTO。
 *
 * @param id             Job 主键
 * @param tenantId       租户 ID
 * @param sceneCode      绑定的 Scene code
 * @param code           Job 编码
 * @param name           Job 名称
 * @param cronExpression Spring 6 段 cron
 * @param subjectQuery   主体查询配置（typed 判别联合）
 * @param eventType      合成 RuleEvent 的 eventType
 * @param status         ACTIVE / DISABLED
 */
public record JobDefinitionDto(
        Long id,
        String tenantId,
        String sceneCode,
        String code,
        String name,
        String cronExpression,
        SubjectQuery subjectQuery,
        String eventType,
        String status
) {}
