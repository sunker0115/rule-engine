package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.connector.TemplateParam;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连接器写时安全校验：endpointRef 已注册、占位符命名空间合法。
 * 资源名集合为 null 时跳过 endpoint 校验（纯 config 部署无 eval catalog，照 MetricSafetyValidator 容错）。
 */
class ConnectorSafetyValidator {

    private static final Pattern PH = Pattern.compile("\\{([a-zA-Z_][\\w.]*)}");
    private static final Set<String> NAMESPACES =
            Set.of("payload", "params", "vars", "subject", "now", "subjectId", "tenantId");

    /**
     * 校验连接器描述符。
     *
     * @param d             连接器描述符
     * @param endpointNames 已注册端点名（null = 跳过 endpoint 校验）
     * @throws IllegalArgumentException 校验失败
     */
    void validate(ConnectorDescriptor d, Set<String> endpointNames) {
        if (endpointNames != null && !endpointNames.contains(d.endpointRef())) {
            throw new IllegalArgumentException("未注册的 endpointRef: " + d.endpointRef());
        }
        // resilience 必填：eval 侧 buildRequest 用 readTimeoutMs 设超时，缺失会 NPE 并被误归 UNAUTHORIZED
        if (d.resilience() == null) {
            throw new IllegalArgumentException("connector descriptor 缺少 resilience（超时/重试策略必填）");
        }
        checkPlaceholders(d.request().pathTemplate());
        if (d.request().bodyTemplate() != null) checkPlaceholders(d.request().bodyTemplate());
        for (TemplateParam p : d.request().query()) checkPlaceholders(p.valueTemplate());
        for (TemplateParam p : d.request().headers()) checkPlaceholders(p.valueTemplate());
    }

    private void checkPlaceholders(String template) {
        Matcher m = PH.matcher(template);
        while (m.find()) {
            String token = m.group(1);
            // 命名空间 = token 中第一个点前部分（无点则整个 token）
            String ns = token.contains(".") ? token.substring(0, token.indexOf('.')) : token;
            if (!NAMESPACES.contains(ns)) {
                throw new IllegalArgumentException("非法占位符命名空间: " + token);
            }
        }
    }
}
