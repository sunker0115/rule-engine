package com.sstlfsj.rule.web.config.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建场景请求体（D13 扩展：含 payloadSchema / eventTypes / dominantMode 等）。
 * payloadSchema / defaultParams 以 Object 接收，由 Controller 序列化为 JSON 字符串传给 Service。
 */
public record CreateSceneRequest(
        @NotBlank String tenantId,
        @NotBlank String sceneCode,
        @NotBlank String name,
        String description,
        String dominantMode,
        String subjectType,
        List<String> eventTypes,
        /** payloadSchema JSON 数组，Jackson 反序列化为 List<Map> 后由 Controller 转回 JSON 字符串。 */
        Object payloadSchema,
        /** defaultParams JSON 对象，Jackson 反序列化为 Map 后由 Controller 转回 JSON 字符串。 */
        Object defaultParams
) {}
