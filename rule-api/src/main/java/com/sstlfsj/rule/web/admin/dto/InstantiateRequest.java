package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/** 从模板实例化规则的请求体。 */
public record InstantiateRequest(
        @NotNull Long tenantId,
        @NotBlank String ruleCode,
        @NotBlank String ruleName,
        @NotBlank String sceneCode,
        List<String> triggerEventTypes,
        Map<String, Object> slotValues
) {}
