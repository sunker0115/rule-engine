package com.sstlfsj.rule.config.api.connector;

/**
 * 错误映射规则：命中 when 时归一到指定细码（细码字面量在 P2 的 MetricFetchError 落地，
 * 此处存 String 名以免 config 反依赖 kernel enum）。
 *
 * @param when 匹配条件
 * @param to   目标细码名，如 "UPSTREAM_ERROR"
 */
public record ErrorRule(ErrorMatch when, String to) {}
