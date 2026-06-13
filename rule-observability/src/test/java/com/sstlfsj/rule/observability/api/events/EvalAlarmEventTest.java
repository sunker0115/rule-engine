package com.sstlfsj.rule.observability.api.events;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvalAlarmEventTest {
    @Test
    void fields_accessible() {
        EvalAlarmEvent e = new EvalAlarmEvent("rule_eval_error_total", 0.05, 0.12, "错误率超阈值");
        assertThat(e.metric()).isEqualTo("rule_eval_error_total");
        assertThat(e.threshold()).isEqualTo(0.05);
        assertThat(e.actual()).isEqualTo(0.12);
        assertThat(e.message()).isEqualTo("错误率超阈值");
    }
}
