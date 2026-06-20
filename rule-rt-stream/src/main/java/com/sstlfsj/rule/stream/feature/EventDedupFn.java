package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * eventId 去重：MapState&lt;eventId,Boolean&gt; + 10min state TTL（OnCreateAndWrite，读不刷新）。
 * 已见过 → drop；未见过 → put + forward。无 eventId 的事件不过滤。
 * Flink 2.0 已删 Time 类，TTL 用 java.time.Duration。
 */
public class EventDedupFn extends KeyedProcessFunction<String, TradeEvent, TradeEvent> {

    private transient MapState<String, Boolean> seenEventIds;

    @Override
    public void open(org.apache.flink.api.common.functions.OpenContext openContext) {
        StateTtlConfig ttl = StateTtlConfig
                .newBuilder(Duration.ofMinutes(10))
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .build();
        MapStateDescriptor<String, Boolean> desc =
                new MapStateDescriptor<>("seenEventIds", String.class, Boolean.class);
        desc.enableTimeToLive(ttl);
        seenEventIds = getRuntimeContext().getMapState(desc);
    }

    @Override
    public void processElement(TradeEvent event, Context ctx, Collector<TradeEvent> out) throws Exception {
        if (event.eventId() == null || event.eventId().isEmpty()) {
            out.collect(event);
            return;
        }
        if (seenEventIds.contains(event.eventId())) return;
        seenEventIds.put(event.eventId(), Boolean.TRUE);
        out.collect(event);
    }
}
