package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.functions.AggregateFunction;

/** RT-D 日累计交易额增量聚合。 */
public class AmountSumAggregateFn implements AggregateFunction<TradeEvent, Double, Double> {
    @Override public Double createAccumulator() { return 0.0; }
    @Override public Double add(TradeEvent event, Double acc) { return acc + event.amount().doubleValue(); }
    @Override public Double getResult(Double acc) { return acc; }
    @Override public Double merge(Double a, Double b) { return a + b; }
}
