package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.model.SecondCount;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 维护每客户最近 300 秒计数（MapState<second,count>），每收到一秒计数即滚动重算 6 个 RT-M 值。
 * 每秒重算（含 0）保证高频后自然回落；迟到秒覆盖后重算。
 */
public class RollingCountFn extends KeyedProcessFunction<String, SecondCount, PartialFeature> {

    private static final int MAX_WINDOW = 300;   // 最大 size（5m）
    private static final FeatureField[] FIELDS = {
            FeatureField.RTM_1S, FeatureField.RTM_10S, FeatureField.RTM_30S,
            FeatureField.RTM_1M, FeatureField.RTM_2M, FeatureField.RTM_5M
    };

    private transient MapState<Long, Long> secondCounts;

    @Override
    public void open(org.apache.flink.api.common.functions.OpenContext openContext) throws Exception {
        secondCounts = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("secondCounts", Long.class, Long.class));
    }

    @Override
    public void processElement(SecondCount sc, Context ctx, Collector<PartialFeature> out) throws Exception {
        secondCounts.put(sc.epochSecond, sc.count);          // 覆盖（迟到秒更新）

        // 清理滑出最大窗口的旧秒
        Iterator<Long> it = secondCounts.keys().iterator();
        while (it.hasNext()) {
            if (sc.epochSecond - it.next() >= MAX_WINDOW) it.remove();
        }

        Map<Long, Long> snapshot = new HashMap<>();
        for (Map.Entry<Long, Long> e : secondCounts.entries()) snapshot.put(e.getKey(), e.getValue());

        long[] sums = RollingWindowState.rollingSums(snapshot, sc.epochSecond);
        long eventTime = sc.epochSecond * 1000;
        for (int i = 0; i < FIELDS.length; i++) {
            out.collect(new PartialFeature(ctx.getCurrentKey(), FIELDS[i], sums[i], eventTime));
        }
    }
}
