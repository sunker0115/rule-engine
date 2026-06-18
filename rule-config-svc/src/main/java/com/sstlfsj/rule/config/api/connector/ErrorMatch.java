package com.sstlfsj.rule.config.api.connector;

/**
 * 错误匹配条件：HTTP 状态区间或响应信封码。两者择一非 null。
 *
 * @param statusFrom   状态码下界（含），null 表示不按状态匹配
 * @param statusTo     状态码上界（含）
 * @param envelopeCode 信封业务码字面量（与 successWhen.path 同位），null 表示不按信封码匹配
 */
public record ErrorMatch(Integer statusFrom, Integer statusTo, Object envelopeCode) {}
