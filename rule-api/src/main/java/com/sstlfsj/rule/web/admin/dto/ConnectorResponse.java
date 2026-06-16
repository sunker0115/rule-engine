package com.sstlfsj.rule.web.admin.dto;

/**
 * 连接器列表项响应。
 *
 * @param connectorCode 编码
 * @param name          名称
 * @param status        状态
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record ConnectorResponse(Long tenantId, String connectorCode, String name, String status,
                                String createdAt, String updatedAt) {}
