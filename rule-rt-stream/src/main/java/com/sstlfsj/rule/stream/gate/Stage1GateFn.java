package com.sstlfsj.rule.stream.gate;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import com.sstlfsj.rule.stream.model.SuspectEvent;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.time.Instant;

/**
 * Stage-1 风险筛选门：主输出全量 FeatureSnapshot（→Redis），侧输出仅 susScore≥阈值的 SuspectEvent（→suspect topic）。
 * OutputTag 用匿名子类创建以保留泛型 TypeInformation。
 */
public class Stage1GateFn extends KeyedProcessFunction<String, FeatureSnapshot, FeatureSnapshot> {

    public static final OutputTag<SuspectEvent> SUSPECT_OUT = new OutputTag<SuspectEvent>("suspect-out") {};

    private final double threshold;

    public Stage1GateFn(double threshold) { this.threshold = threshold; }

    @Override
    public void processElement(FeatureSnapshot snap, Context ctx, Collector<FeatureSnapshot> out) {
        out.collect(snap);   // 主输出：全量 → Redis

        if (snap.susScore >= threshold) {
            SuspectEvent se = new SuspectEvent();
            se.customerId = snap.customerId;
            se.rtmMwr1s = snap.rtmMwr1s;
            se.rtmMwr10s = snap.rtmMwr10s;
            se.rtmMwr1m = snap.rtmMwr1m;
            se.rtmMwr5m = snap.rtmMwr5m;
            se.rtdAmountSum = snap.rtdAmountSum;
            se.fastTradeRatio = snap.fastTradeRatio;
            se.susScore = snap.susScore;
            se.rtState = snap.rtState;
            se.suspectId = snap.customerId + "-" + snap.updatedAt;
            se.occurredAt = Instant.ofEpochSecond(snap.updatedAt);
            ctx.output(SUSPECT_OUT, se);
        }
    }
}
