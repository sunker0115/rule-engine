package com.sstlfsj.rule.observability.internal.alarm;

import com.sstlfsj.rule.observability.api.events.EvalAlarmEvent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;

class ObservabilityAlarmListenerTest {

    @Test
    void onAlarm_does_not_throw() {
        ObservabilityAlarmListener listener = new ObservabilityAlarmListener();
        EvalAlarmEvent event = new EvalAlarmEvent("rule_eval_error_total", 0.05, 0.12, "测试告警");
        assertThatCode(() -> listener.onAlarm(event)).doesNotThrowAnyException();
    }
}
