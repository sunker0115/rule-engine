package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * action 绑定项（请求与响应共用）。defaultParams 为 JSON 对象（{@code Map<String,Object>}，依 actionType 异构），可空。
 *
 * @param actionType    actionType 路由键
 * @param defaultParams Scene 级默认参数，可空
 */
public record ActionBindingItemDto(
        @NotBlank String actionType,
        Map<String, Object> defaultParams
) {}
