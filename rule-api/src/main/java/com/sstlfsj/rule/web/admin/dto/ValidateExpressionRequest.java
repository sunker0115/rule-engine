package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 表达式实时诊断请求。 */
public record ValidateExpressionRequest(
        @NotNull Long tenantId,
        @NotBlank String sceneCode,
        @NotBlank String lang,
        @NotBlank String source
) {}
