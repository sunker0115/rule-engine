package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * action 绑定项（请求与响应共用）。defaultParams / rateLimitOverride 为 JSON 对象（{@code Map<String,Object>}），可空。
 *
 * @param actionType       actionType 路由键
 * @param defaultParams    Scene 级默认参数（依 actionType 异构，故为开放 Map），可空
 * @param rateLimitOverride Scene 级频控覆盖（频控功能未实装，暂为开放 Map），可空
 */
public record ActionBindingItemDto(
        @NotBlank String actionType,
        Map<String, Object> defaultParams,
        Map<String, Object> rateLimitOverride
) {}
