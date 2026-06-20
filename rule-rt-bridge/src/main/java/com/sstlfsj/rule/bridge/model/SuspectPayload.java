package com.sstlfsj.rule.bridge.model;

import java.time.Instant;

/** 消费 rt.suspect.customer 的反序列化体，字段与 rule-stream-rt 的 SuspectEvent 逐字段对齐（JSON 契约复制）。 */
public record SuspectPayload(
        String customerId,
        long rtmMwr1s,
        long rtmMwr10s,
        long rtmMwr1m,
        long rtmMwr5m,
        double rtdAmountSum,
        double fastTradeRatio,
        double susScore,
        String rtState,
        String suspectId,
        Instant occurredAt
) {}
