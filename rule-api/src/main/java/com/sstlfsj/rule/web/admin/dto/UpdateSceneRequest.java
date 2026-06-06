package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 更新场景请求体；所有字段均可选，null 表示不更新该字段。 */
public record UpdateSceneRequest(
        @NotBlank String tenantId,
        String name,
        List<String> eventTypes,
        /** payloadSchema JSON 数组；Jackson 反序列化为 List，避免双栈冲突。 */
        Object payloadSchema,
        /** defaultParams JSON 对象；Jackson 反序列化为 Map，避免双栈冲突。 */
        Object defaultParams
) {}
