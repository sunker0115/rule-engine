package com.sstlfsj.rule.config.api.dto;

import java.util.List;
import java.util.Map;

/** updateScene 的入参 DTO，后续参数>3 的方法统一以此模式收口。 */
public record UpdateSceneCommand(
        String tenantId,
        String sceneCode,
        String name,
        String description,
        List<String> eventTypes,
        List<PayloadFieldSpec> payloadSchema,
        Map<String, Object> defaultParams,
        String actorId
) {}
