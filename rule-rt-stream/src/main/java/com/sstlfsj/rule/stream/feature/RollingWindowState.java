package com.sstlfsj.rule.stream.feature;

import java.util.Map;

/** 按秒计数 → 6 个 RT-M size 的滚动和（无 Flink 依赖，可单测）。 */
public final class RollingWindowState {
    private RollingWindowState() {}

    /** RT-M 6 窗口 size（秒），顺序对齐 FeatureField.RTM_1S..RTM_5M。 */
    public static final int[] SIZES_SECONDS = {1, 10, 30, 60, 120, 300};

    /**
     * 给定每秒计数与当前秒，算 6 个 size 的滚动和。
     * size 窗口 = (currentSecond-size, currentSecond] 即最近 size 个秒槽（age 0..size-1）。
     */
    public static long[] rollingSums(Map<Long, Long> secondCounts, long currentSecond) {
        long[] sums = new long[SIZES_SECONDS.length];
        for (Map.Entry<Long, Long> e : secondCounts.entrySet()) {
            long age = currentSecond - e.getKey();
            if (age < 0) continue;                 // 未来秒（乱序）忽略
            for (int i = 0; i < SIZES_SECONDS.length; i++) {
                if (age < SIZES_SECONDS[i]) sums[i] += e.getValue();
            }
        }
        return sums;
    }
}
