package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.functions.AggregateFunction;

/** 1s 窗口增量计数——仅存一个 long 累加器，不缓冲事件流。 */
public class PerSecondCountFn implements AggregateFunction<TradeEvent, Long, Long> {
    @Override public Long createAccumulator() { return 0L; }
    @Override public Long add(TradeEvent event, Long acc) { return acc + 1; }
    @Override public Long getResult(Long acc) { return acc; }
    @Override public Long merge(Long a, Long b) { return a + b; }
}
