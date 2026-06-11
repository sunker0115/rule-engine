package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class EvalSessionListenerTest {

    @Test
    void lambdaImplementation_isCalled() {
        AtomicBoolean called = new AtomicBoolean(false);
        EvalSessionListener listener = (ev, res) -> called.set(true);

        listener.onSession(
                new RuleEvent("t1", "s1", "E", "sub1", "id1",
                        Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.SDK),
                EvalResult.miss());

        assertThat(called.get()).isTrue();
    }
}
