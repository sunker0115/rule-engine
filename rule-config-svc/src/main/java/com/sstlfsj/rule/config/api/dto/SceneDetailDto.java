package com.sstlfsj.rule.config.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Scene 详情响应 DTO（D13），包含 payloadSchema / eventTypes / defaultParams 等元数据。
 */
public record SceneDetailDto(
        Long id,
        String tenantId,
        String sceneCode,
        String name,
        String description,
        String dominantMode,
        String subjectType,
        List<String> eventTypes,
        List<PayloadFieldSpec> payloadSchema,
        Map<String, Object> defaultParams,
        String status
) {}
