package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;

/**
 * 连接器写请求体。
 *
 * @param name       展示名
 * @param descriptor 连接器描述符（typed，前端按 JSON Schema 构造）
 */
public record ConnectorRequest(String name, ConnectorDescriptor descriptor) {}
