package com.sstlfsj.rule.web.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 创建规则草稿请求体。 */
public record CreateRuleRequest(
        @NotBlank String tenantId,
        @NotNull Long sceneId,
        @NotBlank String code,
        @NotBlank String name
) {}
