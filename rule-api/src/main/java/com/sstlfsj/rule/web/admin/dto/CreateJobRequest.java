package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建 Job 请求体（D11）。
 * subjectQuery / payloadTemplate 以 Object 接收，由 Controller 序列化为 JSON 字符串传给 Service。
 */
public record CreateJobRequest(
        @NotBlank String tenantId,
        @NotBlank String sceneCode,
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String cronExpression,
        /** 主体查询配置 JSON 对象，如 {"type":"SQL","sql":"..."}。 */
        @NotNull Object subjectQuery,
        @NotBlank String eventType,
        /** payload 模板 JSON 对象（可选），占位符按主体行同名字段填充。 */
        Object payloadTemplate
) {}
