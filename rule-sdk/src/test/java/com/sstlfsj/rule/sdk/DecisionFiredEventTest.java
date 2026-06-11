package com.sstlfsj.rule.sdk;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionFiredEventTest {
    @Test
    void decision_matchesByCode() {
        DecisionFiredEvent e = new DecisionFiredEvent("REVIEW", 50, null, null, null);
        assertThat(e.decision("REVIEW")).isTrue();
        assertThat(e.decision("REJECT")).isFalse();
        assertThat(e.priority()).isEqualTo(50);
    }
}
