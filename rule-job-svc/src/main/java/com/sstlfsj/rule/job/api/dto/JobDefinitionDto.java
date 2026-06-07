package com.sstlfsj.rule.job.api.dto;

/**
 * Job 定义响应 DTO。
 *
 * @param id              Job 主键
 * @param tenantId        租户 ID
 * @param sceneCode       绑定的 Scene code
 * @param code            Job 编码
 * @param name            Job 名称
 * @param cronExpression  Spring 6 段 cron
 * @param subjectQuery    主体查询配置 JSON
 * @param eventType       合成 RuleEvent 的 eventType
 * @param payloadTemplate payload 模板 JSON
 * @param status          ACTIVE / DISABLED
 */
public record JobDefinitionDto(
        Long id,
        String tenantId,
        String sceneCode,
        String code,
        String name,
        String cronExpression,
        String subjectQuery,
        String eventType,
        String payloadTemplate,
        String status
) {}
