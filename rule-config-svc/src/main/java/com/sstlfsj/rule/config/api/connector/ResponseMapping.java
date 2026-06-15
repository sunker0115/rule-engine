package com.sstlfsj.rule.config.api.connector;

/**
 * 响应映射：从任意外壳归一到 metric 值。
 *
 * @param successWhen 成功判定谓词
 * @param valuePath   取值点号 jsonPath，如 "data.score"
 */
public record ResponseMapping(Predicate successWhen, String valuePath) {}
