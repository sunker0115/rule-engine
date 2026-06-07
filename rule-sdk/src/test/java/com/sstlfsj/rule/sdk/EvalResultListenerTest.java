package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EvalResultListenerTest {

    @Test
    void lambdaImplementation_receivesEventAndResult() {
        AtomicReference<RuleEvent> capturedEvent = new AtomicReference<>();
        AtomicReference<EvalResult> capturedResult = new AtomicReference<>();

        EvalResultListener listener = (ev, res) -> {
            capturedEvent.set(ev);
            capturedResult.set(res);
        };

        RuleEvent event = new RuleEvent("t1", "s1", "E", "sub1", "id1",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
        EvalResult result = EvalResult.miss();

        listener.onResult(event, result);

        assertThat(capturedEvent.get()).isSameAs(event);
        assertThat(capturedResult.get()).isSameAs(result);
    }
}
