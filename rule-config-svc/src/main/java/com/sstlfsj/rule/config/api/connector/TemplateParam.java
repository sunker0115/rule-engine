package com.sstlfsj.rule.config.api.connector;

/**
 * 请求模板参数（query / header 用）。
 *
 * @param name          参数名
 * @param valueTemplate 含占位符的值模板，如 "{vars.userId}"
 */
public record TemplateParam(String name, String valueTemplate) {}
