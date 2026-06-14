package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/** 更新场景请求体；所有字段均可选，null 表示不更新该字段。 */
public record UpdateSceneRequest(
        @NotBlank String tenantId,
        String name,
        String description,
        List<String> eventTypes,
        List<PayloadFieldSpec> payloadSchema,
        Map<String, Object> defaultParams
) {}
