package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * RT-D 日累计交易额：每笔交易即 emit 当前 UTC 自然日累计（日内实时，不等日窗口结束）。
 * 跨日（事件 epochDay &gt; state 日）自动重置累计。eventTime 取事件发生时刻，与 RT-M 量级一致，
 * 避免日窗口 end（次日）作为 eventTime 顶掉下游 merger 的去抖 timer。
 */
public class DailyAmountFn extends KeyedProcessFunction<String, TradeEvent, PartialFeature> {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private transient ValueState<Double> dailySum;
    private transient ValueState<Long> currentDay;

    @Override
    public void open(org.apache.flink.api.common.functions.OpenContext openContext) throws Exception {
        dailySum = getRuntimeContext().getState(new ValueStateDescriptor<>("dailySum", Double.class));
        currentDay = getRuntimeContext().getState(new ValueStateDescriptor<>("currentDay", Long.class));
    }

    @Override
    public void processElement(TradeEvent event, Context ctx, Collector<PartialFeature> out) throws Exception {
        long eventMillis = event.occurredAt().toEpochMilli();
        long epochDay = eventMillis / MILLIS_PER_DAY;       // UTC 自然日

        Long day = currentDay.value();
        double sum = (day != null && day == epochDay && dailySum.value() != null) ? dailySum.value() : 0.0;

        sum += event.amount().doubleValue();
        dailySum.update(sum);
        currentDay.update(epochDay);

        out.collect(new PartialFeature(ctx.getCurrentKey(), FeatureField.RTD_AMOUNT, sum, eventMillis));
    }
}
