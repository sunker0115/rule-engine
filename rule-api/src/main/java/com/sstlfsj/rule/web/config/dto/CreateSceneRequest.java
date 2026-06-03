package com.sstlfsj.rule.web.config.dto;

import jakarta.validation.constraints.NotBlank;

/** 创建场景请求体。 */
public record CreateSceneRequest(
        @NotBlank String tenantId,
        @NotBlank String sceneCode,
        @NotBlank String name
) {}
