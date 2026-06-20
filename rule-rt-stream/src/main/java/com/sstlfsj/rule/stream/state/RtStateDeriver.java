package com.sstlfsj.rule.stream.state;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;

/** 特征快照 → RT 状态（静态，零依赖）。 */
public final class RtStateDeriver {
    private RtStateDeriver() {}

    public static final String RT_CLEAN = "RT_CLEAN";
    public static final String RT_WATCH = "RT_WATCH";
    public static final String SHORT_ALPHA = "SHORT_ALPHA";
    public static final String LATENCY_ARB = "LATENCY_ARB";

    /** 按优先级判状态，命中即返回。 */
    public static String derive(FeatureSnapshot s) {
        if (s.fastTradeRatio > 0.8 && s.rtmMwr1s > 8) return LATENCY_ARB;
        if (s.susScore >= 0.6 && s.rtmMwr1s > 10) return SHORT_ALPHA;
        if (s.susScore >= 0.3 || s.fastTradeRatio > 0.3) return RT_WATCH;
        return RT_CLEAN;
    }
}
