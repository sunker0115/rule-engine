package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * 创建场景请求体（D13 扩展：含 payloadSchema / eventTypes / dominantMode 等）。
 * payloadSchema 为 typed 字段声明列表；defaultParams 为开放配置（动态），由 Controller 序列化为 JSON 字符串传给 Service。
 */
public record CreateSceneRequest(
        @NotBlank String tenantId,
        @NotBlank String sceneCode,
        @NotBlank String name,
        String description,
        String dominantMode,
        String subjectType,
        List<String> eventTypes,
        List<PayloadFieldSpec> payloadSchema,
        Map<String, Object> defaultParams
) {}
