package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Scene 变更前/后快照,落 audit_log 的 before/after_snapshot(取代专用 payloadSchema 历史表)。
 *
 * @param name          场景名
 * @param eventTypes    允许 eventType 白名单
 * @param payloadSchema payloadSchema 字段声明
 * @param defaultParams 默认参数
 * @param status        场景状态(ACTIVE/DISABLED)
 */
@Builder
public record SceneSnapshot(String name, List<String> eventTypes,
                            List<PayloadFieldSpec> payloadSchema,
                            Map<String, Object> defaultParams, String status)
        implements AuditSnapshot {
}
