package com.sstlfsj.rule.stream.model;

/** 合并后的完整 RT 特征快照。FeatureSnapshotMerger 算出派生字段后写 Redis。 */
public class FeatureSnapshot {
    public String customerId;
    // RT-M 6 窗口滚动计数
    public long rtmMwr1s, rtmMwr10s, rtmMwr30s, rtmMwr1m, rtmMwr2m, rtmMwr5m;
    // RT-D
    public double rtdAmountSum;
    // RT-B
    public double fastTradeRatio;
    // 派生
    public double susScore;
    public String rtState;
    // event-time epoch second（引擎侧新鲜度校验用）
    public long updatedAt;

    public FeatureSnapshot() {}

    public FeatureSnapshot(String customerId) {
        this.customerId = customerId;
    }
}
