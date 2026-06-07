package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobTargetTest {

    @Test
    void of_subjectId_emptyPayloadAndMetrics() {
        JobTarget t = JobTarget.of("u1");
        assertThat(t.subjectId()).isEqualTo("u1");
        assertThat(t.payload()).isEmpty();
        assertThat(t.providedMetrics()).isEmpty();
    }

    @Test
    void of_subjectIdAndPayload_carriesPayload() {
        JobTarget t = JobTarget.of("u1", Map.of("k", "v"));
        assertThat(t.payload()).containsEntry("k", "v");
        assertThat(t.providedMetrics()).isEmpty();
    }

    @Test
    void withProvidedMetrics_returnsNewInstanceWithMetrics() {
        JobTarget base = JobTarget.of("u1", Map.of("k", "v"));
        JobTarget withMetrics = base.withProvidedMetrics(Map.of("score", 0.9));

        assertThat(withMetrics.subjectId()).isEqualTo("u1");
        assertThat(withMetrics.payload()).containsEntry("k", "v");
        assertThat(withMetrics.providedMetrics()).containsEntry("score", 0.9);
        // 原实例不变（record 不可变）
        assertThat(base.providedMetrics()).isEmpty();
    }

    @Test
    void blankSubjectId_rejected() {
        assertThatThrownBy(() -> JobTarget.of("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobTarget.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
