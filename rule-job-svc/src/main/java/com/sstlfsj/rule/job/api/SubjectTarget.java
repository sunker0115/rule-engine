package com.sstlfsj.rule.job.api;

import java.util.Map;

/**
 * {@code @TriggerTask} 业务查询方法的返回元素：合成 RuleEvent 的素材。
 *
 * @param subjectId       主体标识，不可空（合成 RuleEvent 的 subjectId）
 * @param payload         进 RuleEvent.payload，可空
 * @param providedMetrics 进 RuleEvent.providedMetrics（预提供值，引擎优先采用），可空
 */
public record SubjectTarget(String subjectId, Map<String, Object> payload, Map<String, Object> providedMetrics) {

    public SubjectTarget {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("SubjectTarget.subjectId 不能为空");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        providedMetrics = providedMetrics == null ? Map.of() : Map.copyOf(providedMetrics);
    }

    /** 只有 subjectId 的目标（无 payload、无预提供指标）。 */
    public static SubjectTarget of(String subjectId) {
        return new SubjectTarget(subjectId, Map.of(), Map.of());
    }

    /** subjectId + payload 的目标。 */
    public static SubjectTarget of(String subjectId, Map<String, Object> payload) {
        return new SubjectTarget(subjectId, payload, Map.of());
    }

    /** 在现有目标上补充预提供指标，返回新实例。 */
    public SubjectTarget withProvidedMetrics(Map<String, Object> providedMetrics) {
        return new SubjectTarget(subjectId, payload, providedMetrics);
    }
}
