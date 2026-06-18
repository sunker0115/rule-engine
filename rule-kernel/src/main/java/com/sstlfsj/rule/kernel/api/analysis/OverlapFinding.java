package com.sstlfsj.rule.kernel.api.analysis;

/** 输入区间相交且决策相同(冗余/可合并提示)。 */
public record OverlapFinding(String locA, String locB, String reason, Severity severity) {
}
