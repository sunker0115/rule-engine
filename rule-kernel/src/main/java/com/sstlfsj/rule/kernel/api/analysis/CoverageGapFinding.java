package com.sstlfsj.rule.kernel.api.analysis;

/** 绑定到 scene 但无任何规则路径产出的 Decision。 */
public record CoverageGapFinding(String decisionCode, String reason, Severity severity) {
}
