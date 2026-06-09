package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 整组覆盖式保存 action 绑定请求体。{@code bindings} 为目标全量集合，
 * 空列表表示清空该场景全部绑定；null 等同空列表。
 *
 * @param tenantId 租户 ID（必填）
 * @param bindings 目标绑定全量集合，可空
 */
public record ReplaceActionBindingsRequest(
        @NotBlank String tenantId,
        @Valid List<ActionBindingItemDto> bindings
) {}
