package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.state.RtStateDeriver;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 合并 RT-M/RT-D/RT-B 三条 PartialFeature 流。维护 ValueState<FeatureSnapshot>，按 field 覆盖对应字段（含 0 → 回落）。
 * event-time timer（eventTime+200ms）批量合并，避免同客户多字段短时内触发重复写 Redis。
 * updated_at 取 timer 的 event-time（非墙钟），保证回放可复现、新鲜度准确。
 */
public class FeatureSnapshotMerger extends KeyedProcessFunction<String, PartialFeature, FeatureSnapshot> {

    private transient ValueState<FeatureSnapshot> snapshot;
    private transient ValueState<Long> pendingTimer;

    @Override
    public void open(org.apache.flink.api.common.functions.OpenContext openContext) throws Exception {
        snapshot = getRuntimeContext().getState(new ValueStateDescriptor<>("snapshot", FeatureSnapshot.class));
        pendingTimer = getRuntimeContext().getState(new ValueStateDescriptor<>("pendingTimer", Long.class));
    }

    @Override
    public void processElement(PartialFeature p, Context ctx, Collector<FeatureSnapshot> out) throws Exception {
        FeatureSnapshot cur = snapshot.value();
        if (cur == null) cur = new FeatureSnapshot(ctx.getCurrentKey());

        switch (p.field) {                         // 覆盖写（含 0 → 自然回落）
            case RTM_1S -> cur.rtmMwr1s = (long) p.value;
            case RTM_10S -> cur.rtmMwr10s = (long) p.value;
            case RTM_30S -> cur.rtmMwr30s = (long) p.value;
            case RTM_1M -> cur.rtmMwr1m = (long) p.value;
            case RTM_2M -> cur.rtmMwr2m = (long) p.value;
            case RTM_5M -> cur.rtmMwr5m = (long) p.value;
            case RTD_AMOUNT -> cur.rtdAmountSum = p.value;
            case FAST_TRADE_RATIO -> cur.fastTradeRatio = p.value;
        }
        snapshot.update(cur);

        long fireTime = p.eventTime + 200;
        Long existing = pendingTimer.value();
        if (existing != null) ctx.timerService().deleteEventTimeTimer(existing);
        ctx.timerService().registerEventTimeTimer(fireTime);
        pendingTimer.update(fireTime);
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<FeatureSnapshot> out) throws Exception {
        FeatureSnapshot cur = snapshot.value();
        if (cur == null) return;
        cur.susScore = SusScoreFn.compute(cur.rtmMwr1s, cur.fastTradeRatio, cur.rtmMwr1m);
        cur.rtState = RtStateDeriver.derive(cur);
        cur.updatedAt = timestamp / 1000;          // event-time（timer 触发时刻），非墙钟
        out.collect(cur);
        pendingTimer.clear();
    }
}
