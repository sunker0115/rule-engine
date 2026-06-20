package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.PartialFeature;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/** 给 RT-D 日累计补 customerId + 窗口结束时间 → PartialFeature(RTD_AMOUNT)。 */
public class AmountTagFn extends ProcessWindowFunction<Double, PartialFeature, String, TimeWindow> {
    @Override
    public void process(String customerId, Context ctx, Iterable<Double> sums, Collector<PartialFeature> out) {
        double sum = sums.iterator().next();
        out.collect(new PartialFeature(customerId, FeatureField.RTD_AMOUNT, sum, ctx.window().getEnd()));
    }
}
