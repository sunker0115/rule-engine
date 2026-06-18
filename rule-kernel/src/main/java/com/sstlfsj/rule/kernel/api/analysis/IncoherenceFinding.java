package com.sstlfsj.rule.kernel.api.analysis;

/** 规则自身条件矛盾(必死)。 */
public record IncoherenceFinding(String ruleCode, String reason, Severity severity) {
}
