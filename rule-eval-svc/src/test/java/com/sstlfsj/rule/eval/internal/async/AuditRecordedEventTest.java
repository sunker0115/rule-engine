package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.domain.EvalMode;
import com.sstlfsj.rule.eval.internal.event.Durability;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRecordedEventTest {

    @Test
    void carriesCandidateVersionIds_andIsBestEffort() {
        AuditRecordedEvent e = new AuditRecordedEvent(
                1L, null, EvalMode.PULL, 2, EvalResult.miss(), null, null, 5, List.of(11L, 22L));
        assertThat(e.candidateVersionIds()).containsExactly(11L, 22L);
        assertThat(e.durationMs()).isEqualTo(5);
        assertThat(e.durability()).isEqualTo(Durability.BEST_EFFORT);
    }
}
