package com.sstlfsj.rule.eval.internal.domain;

/** evaluation_session 命中决策快照。 */
public record HitDecision(String code, String category, Long ruleVersionId) {
}
