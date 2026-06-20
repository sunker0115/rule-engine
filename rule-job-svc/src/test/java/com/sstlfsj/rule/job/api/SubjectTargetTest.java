package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubjectTargetTest {

    @Test
    void of_subjectId_emptyPayloadAndMetrics() {
        SubjectTarget t = SubjectTarget.of("u1");
        assertThat(t.subjectId()).isEqualTo("u1");
        assertThat(t.payload()).isEmpty();
        assertThat(t.providedMetrics()).isEmpty();
    }

    @Test
    void of_subjectIdAndPayload_carriesPayload() {
        SubjectTarget t = SubjectTarget.of("u1", Map.of("k", "v"));
        assertThat(t.payload()).containsEntry("k", "v");
        assertThat(t.providedMetrics()).isEmpty();
    }

    @Test
    void withProvidedMetrics_returnsNewInstanceWithMetrics() {
        SubjectTarget base = SubjectTarget.of("u1", Map.of("k", "v"));
        SubjectTarget withMetrics = base.withProvidedMetrics(Map.of("score", 0.9));

        assertThat(withMetrics.subjectId()).isEqualTo("u1");
        assertThat(withMetrics.payload()).containsEntry("k", "v");
        assertThat(withMetrics.providedMetrics()).containsEntry("score", 0.9);
        // 原实例不变（record 不可变）
        assertThat(base.providedMetrics()).isEmpty();
    }

    @Test
    void blankSubjectId_rejected() {
        assertThatThrownBy(() -> SubjectTarget.of("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubjectTarget.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
