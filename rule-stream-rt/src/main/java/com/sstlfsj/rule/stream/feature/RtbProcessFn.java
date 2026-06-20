package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/** RT-B：5min 滑动窗口内 API 通道占比。需遍历窗口事件，用 ProcessWindowFunction。 */
public class RtbProcessFn extends ProcessWindowFunction<TradeEvent, PartialFeature, String, TimeWindow> {
    @Override
    public void process(String customerId, Context ctx, Iterable<TradeEvent> events, Collector<PartialFeature> out) {
        int total = 0, api = 0;
        for (TradeEvent e : events) { total++; if ("API".equals(e.channel())) api++; }
        double ratio = total > 0 ? (double) api / total : 0;
        out.collect(new PartialFeature(customerId, FeatureField.FAST_TRADE_RATIO, ratio, ctx.window().getEnd()));
    }
}
