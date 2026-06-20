package com.sstlfsj.rule.stream.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Kafka 交易流反序列化 record。事件时间 = occurredAt（与引擎同源）。 */
public record TradeEvent(
        String customerId,
        String instrument,
        BigDecimal amount,
        String channel,       // APP / WEB / API
        Instant occurredAt,
        String eventId
) {}
