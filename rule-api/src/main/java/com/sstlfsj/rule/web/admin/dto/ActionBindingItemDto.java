package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * action 绑定项（请求与响应共用）。defaultParams / rateLimitOverride 为任意 JSON 对象，可空。
 *
 * @param actionType       actionType 路由键
 * @param defaultParams    Scene 级默认参数对象，可空
 * @param rateLimitOverride Scene 级频控覆盖对象，可空
 */
public record ActionBindingItemDto(
        @NotBlank String actionType,
        Object defaultParams,
        Object rateLimitOverride
) {}
