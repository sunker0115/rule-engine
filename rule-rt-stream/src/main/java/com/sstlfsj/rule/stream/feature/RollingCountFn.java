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
 * 维护每客户最近 300 秒计数（MapState&lt;second,count&gt;），滚动求 6 个 RT-M 值。
 * 收到计数即重算，并注册下一秒 event-time timer：即使此后无新交易，watermark 推进也会
 * 逐秒重算让计数自然回落——老秒滑出窗口后归零（避免高频后卡在峰值直到 TTL）。
 * state 全部滑出（无活跃秒）后 emit 一次全 0 并停止续约 timer，不空转。
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
        recomputeAndEmit(sc.epochSecond, ctx.getCurrentKey(), out);
        // 注册下一秒 timer，驱动无新交易时的逐秒回落
        ctx.timerService().registerEventTimeTimer((sc.epochSecond + 1) * 1000);
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<PartialFeature> out) throws Exception {
        long currentSecond = timestamp / 1000;
        boolean hasState = recomputeAndEmit(currentSecond, ctx.getCurrentKey(), out);
        // 仍有活跃秒则续约继续回落；全部滑出则停止（已 emit 全 0）
        if (hasState) {
            ctx.timerService().registerEventTimeTimer((currentSecond + 1) * 1000);
        }
    }

    /** 清理滑出秒 + 滚动求和 emit 6 个 RT-M。返回 state 是否仍非空。 */
    private boolean recomputeAndEmit(long currentSecond, String key, Collector<PartialFeature> out) throws Exception {
        Iterator<Long> it = secondCounts.keys().iterator();
        while (it.hasNext()) {
            if (currentSecond - it.next() >= MAX_WINDOW) it.remove();
        }

        Map<Long, Long> snapshot = new HashMap<>();
        for (Map.Entry<Long, Long> e : secondCounts.entries()) snapshot.put(e.getKey(), e.getValue());

        long[] sums = RollingWindowState.rollingSums(snapshot, currentSecond);
        long eventTime = currentSecond * 1000;
        for (int i = 0; i < FIELDS.length; i++) {
            out.collect(new PartialFeature(key, FIELDS[i], sums[i], eventTime));
        }
        return !snapshot.isEmpty();
    }
}
