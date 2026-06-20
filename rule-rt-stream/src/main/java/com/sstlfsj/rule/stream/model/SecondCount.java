package com.sstlfsj.rule.stream.model;

/** 1s 桶计数：customerId + 该秒笔数 + epoch second。喂 RollingCountFn。 */
public class SecondCount {
    public String customerId;
    public long count;
    public long epochSecond;

    public SecondCount() {}

    public SecondCount(String customerId, long count, long epochSecond) {
        this.customerId = customerId;
        this.count = count;
        this.epochSecond = epochSecond;
    }
}
