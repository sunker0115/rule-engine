package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record CreateSceneRequest(
        @NotNull Long tenantId,
        @NotBlank String sceneCode,
        @NotBlank String name,
        String description,
        String dominantMode,
        String subjectType,
        List<String> eventTypes,
        List<PayloadFieldSpec> payloadSchema,
        Map<String, Object> defaultParams
) {}
