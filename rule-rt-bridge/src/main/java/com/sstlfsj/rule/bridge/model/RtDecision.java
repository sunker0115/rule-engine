package com.sstlfsj.rule.bridge.model;

import java.time.Instant;

/** 引擎决策结果，发往 rt.decision 供下游 CustomerRiskProfile 状态机消费。 */
public record RtDecision(
        String customerId,
        String decision,
        String suspectId,
        Instant occurredAt
) {}
