package com.sstlfsj.rule.stream.model;

import java.time.Instant;

/** Stage-1 风险筛选门过门事件，emit 到 rt.suspect.customer。typed 字段（非 Map），跨模块 JSON 契约。 */
public class SuspectEvent {
    public String customerId;
    public long rtmMwr1s;
    public long rtmMwr10s;
    public long rtmMwr1m;
    public long rtmMwr5m;
    public double rtdAmountSum;
    public double fastTradeRatio;
    public double susScore;
    public String rtState;
    public String suspectId;       // customerId + "-" + updatedAt，幂等键
    public Instant occurredAt;     // event-time

    public SuspectEvent() {}
}
