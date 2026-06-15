package com.sstlfsj.rule.config.api.connector;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Builder;

/**
 * 熔断策略。
 *
 * @param failureRateThreshold 失败率阈值（0-100）
 * @param windowSeconds        统计窗口秒
 * @param openSeconds          打开后保持秒
 */
@Builder
public record CircuitBreakerPolicy(
        @JsonSetter(nulls = Nulls.AS_EMPTY) int failureRateThreshold,
        @JsonSetter(nulls = Nulls.AS_EMPTY) int windowSeconds,
        @JsonSetter(nulls = Nulls.AS_EMPTY) int openSeconds) {}
