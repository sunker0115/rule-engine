package com.sstlfsj.rule.config.api.dto;

/** Scene 列表项：场景选择器 / 列表页展示用的精简字段。 */
public record SceneListItem(Long id, Long tenantId, String sceneCode, String name,
                            String dominantMode, String subjectType, String status,
                            java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {}
