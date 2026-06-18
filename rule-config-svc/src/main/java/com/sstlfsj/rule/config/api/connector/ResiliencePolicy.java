package com.sstlfsj.rule.config.api.connector;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Builder;
import java.util.Set;

/**
 * 弹性策略。retry 仅对幂等请求生效；SQL 侧仅用到 readTimeoutMs（statement 超时，P2）。
 *
 * @param connectTimeoutMs 连接超时毫秒
 * @param readTimeoutMs    读超时毫秒
 * @param retries          重试次数
 * @param retryOn          触发重试的条件
 * @param circuitBreaker   熔断策略，可为 null（不启用）
 */
@Builder
public record ResiliencePolicy(
        @JsonSetter(nulls = Nulls.AS_EMPTY) int connectTimeoutMs,
        @JsonSetter(nulls = Nulls.AS_EMPTY) int readTimeoutMs,
        @JsonSetter(nulls = Nulls.AS_EMPTY) int retries,
        Set<RetryTrigger> retryOn,
        CircuitBreakerPolicy circuitBreaker) {}
