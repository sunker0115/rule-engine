package com.sstlfsj.rule.stream.gate;

/** Stage-1 风险筛选门阈值（P3 常量，P4 改动态配置）。 */
public final class ThresholdConfig {
    private ThresholdConfig() {}

    /** susScore ≥ 0.5 触发 suspect event（落在 RtStateDeriver 的 RT_WATCH(0.3)~SHORT_ALPHA(0.6) 之间）。 */
    public static final double DEFAULT_SUS_SCORE_THRESHOLD = 0.5;
}
