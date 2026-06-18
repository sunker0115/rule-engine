package com.sstlfsj.rule.config.api.connector;

/**
 * 响应成功判定谓词。{@code value} 为异构标量字面量（Number/String/Boolean），
 * 是 CLAUDE.md 允许的"确实无固定类型"例外。
 *
 * @param path  点号 jsonPath，如 "code"
 * @param op    比较算子
 * @param value 比较字面量
 */
public record Predicate(String path, CompareOp op, Object value) {}
