package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;

/**
 * 连接器详情响应（含 typed descriptor，供前端编辑器加载）。
 *
 * @param connectorCode 编码
 * @param name          名称
 * @param descriptor    连接器描述符（typed，不转 String）
 * @param status        状态
 */
public record ConnectorDetailResponse(String connectorCode, String name,
                                      ConnectorDescriptor descriptor, String status) {}
