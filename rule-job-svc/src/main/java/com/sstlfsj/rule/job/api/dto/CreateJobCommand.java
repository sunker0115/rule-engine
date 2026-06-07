package com.sstlfsj.rule.job.api.dto;

/**
 * 创建 Job 的入参聚合。
 *
 * @param tenantId        租户 ID
 * @param sceneCode       绑定的 Scene code（须为 PUSH / HYBRID）
 * @param code            Job 编码，租户 + 场景内唯一
 * @param name            Job 名称
 * @param cronExpression  Spring 6 段 cron
 * @param subjectQuery    主体查询配置 JSON
 * @param eventType       合成 RuleEvent 的 eventType
 * @param payloadTemplate payload 模板 JSON（可为 null）
 * @param actorId         操作人 ID
 */
public record CreateJobCommand(
        String tenantId,
        String sceneCode,
        String code,
        String name,
        String cronExpression,
        String subjectQuery,
        String eventType,
        String payloadTemplate,
        String actorId
) {}
