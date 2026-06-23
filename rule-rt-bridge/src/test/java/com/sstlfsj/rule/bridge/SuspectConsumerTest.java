package com.sstlfsj.rule.bridge;

import com.sstlfsj.rule.bridge.model.RtDecision;
import com.sstlfsj.rule.bridge.model.SuspectPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuspectConsumerTest {

    private SuspectPayload payload() {
        return new SuspectPayload("c1", 9, 9, 9, 9, 500.0, 1.0, 0.7, "SHORT_ALPHA",
                "c1-100", Instant.parse("2026-06-20T07:00:00Z"));
    }

    @Test
    void publishesDecisionWhenEngineReturnsCode() {
        EvalClient eval = mock(EvalClient.class);
        DecisionPublisher publisher = mock(DecisionPublisher.class);
        when(eval.evaluate(any())).thenReturn("HIGH");

        org.mockito.ArgumentCaptor<RtDecision> captor = org.mockito.ArgumentCaptor.forClass(RtDecision.class);
        new SuspectConsumer(eval, publisher).onSuspect(payload());

        verify(publisher).publish(captor.capture());
        RtDecision sent = captor.getValue();
        assertThat(sent.customerId()).isEqualTo("c1");
        assertThat(sent.decision()).isEqualTo("HIGH");
        assertThat(sent.suspectId()).isEqualTo("c1-100");
    }

    @Test
    void skipsPublishWhenDecisionNull() {
        EvalClient eval = mock(EvalClient.class);
        DecisionPublisher publisher = mock(DecisionPublisher.class);
        when(eval.evaluate(any())).thenReturn(null);

        new SuspectConsumer(eval, publisher).onSuspect(payload());

        verify(publisher, never()).publish(any());
    }

    @Test
    void swallowsEvalExceptionWithoutPublishing() {
        EvalClient eval = mock(EvalClient.class);
        DecisionPublisher publisher = mock(DecisionPublisher.class);
        when(eval.evaluate(any())).thenThrow(new RuntimeException("engine down"));

        // 不抛出（offset 仍提交），且不发决策
        new SuspectConsumer(eval, publisher).onSuspect(payload());

        verify(publisher, never()).publish(any());
    }
}
