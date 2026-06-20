package com.sstlfsj.rule.stream.feature;

/** sus_score 加权计算（静态，零依赖）。 */
public final class SusScoreFn {
    private SusScoreFn() {}

    public static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    public static double compute(double rtm1s, double fastTradeRatio, double rtm1m) {
        return clamp((rtm1s / 10.0) * 0.3 + (fastTradeRatio / 0.8) * 0.4 + (rtm1m / 30.0) * 0.3);
    }
}
