package com.sstlfsj.rule.web.admin.dto;

/**
 * 连接器列表项响应。
 *
 * @param connectorCode 编码
 * @param name          名称
 * @param status        状态
 */
public record ConnectorResponse(String connectorCode, String name, String status) {}
