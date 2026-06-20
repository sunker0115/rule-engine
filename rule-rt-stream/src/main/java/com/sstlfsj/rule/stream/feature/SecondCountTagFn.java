package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.SecondCount;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/** 给每秒计数补 customerId + 窗口结束秒（epoch second）。 */
public class SecondCountTagFn extends ProcessWindowFunction<Long, SecondCount, String, TimeWindow> {
    @Override
    public void process(String customerId, Context ctx, Iterable<Long> counts, Collector<SecondCount> out) {
        long count = counts.iterator().next();
        long epochSecond = ctx.window().getEnd() / 1000;   // 窗口结束时刻对应秒
        out.collect(new SecondCount(customerId, count, epochSecond));
    }
}
