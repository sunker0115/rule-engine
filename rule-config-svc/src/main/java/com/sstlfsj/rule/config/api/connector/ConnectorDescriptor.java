package com.sstlfsj.rule.config.api.connector;

import lombok.Builder;
import java.util.List;

/**
 * 声明式 HTTP 连接器描述符（设计 §5）。整体作为 connector_definition 单 JSON 列存储。
 *
 * @param endpointRef  指向已注册传输层 Endpoint 名
 * @param request      请求模板
 * @param response     响应映射
 * @param auth         鉴权方案
 * @param resilience   弹性策略
 * @param errorMapping 错误映射规则列表
 */
@Builder
public record ConnectorDescriptor(
        String endpointRef,
        HttpRequestTemplate request,
        ResponseMapping response,
        AuthScheme auth,
        ResiliencePolicy resilience,
        List<ErrorRule> errorMapping) {}
