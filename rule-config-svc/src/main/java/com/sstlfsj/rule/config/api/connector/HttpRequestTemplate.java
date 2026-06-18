package com.sstlfsj.rule.config.api.connector;

import lombok.Builder;
import java.util.List;

/**
 * HTTP 请求模板。占位符 {payload.x}/{params.x}/{vars.x}/{subject.x}/{now}/{subjectId}/{tenantId}。
 *
 * @param method       请求方法
 * @param pathTemplate 含占位符的相对路径
 * @param query        query 参数模板列表
 * @param headers      header 模板列表
 * @param bodyTemplate POST/PUT 的请求体模板，含占位符；GET 为 null
 */
@Builder
public record HttpRequestTemplate(
        HttpMethod method,
        String pathTemplate,
        List<TemplateParam> query,
        List<TemplateParam> headers,
        String bodyTemplate) {}
