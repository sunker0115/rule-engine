package com.sstlfsj.rule.eval.internal.domain;

import java.time.Instant;
import java.util.Map;

/** evaluation_session 评估上下文快照。 */
public record EvaluationContextSnapshot(Map<String, Object> metrics, Instant evalNow) {
}
