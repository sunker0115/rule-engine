package com.sstlfsj.rule.web.api.dto;

/** PUSH 评估接受响应：事件 ID 与是否已接受入队。 */
public record PushEventResponse(String eventId, boolean accepted) {}
