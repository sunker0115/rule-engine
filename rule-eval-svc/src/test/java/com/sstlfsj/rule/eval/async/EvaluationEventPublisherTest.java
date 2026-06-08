package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.async.ActionDeliveryChannel;
import com.sstlfsj.rule.eval.internal.async.ActionRequested;
import com.sstlfsj.rule.eval.internal.async.AuditRecorded;
import com.sstlfsj.rule.eval.internal.async.EvaluationEventPublisher;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 验证审计事件走 ApplicationEventPublisher、action 事件走 ActionDeliveryChannel。 */
class EvaluationEventPublisherTest {

    private RuleEvent event() {
        return RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e1").source(EventSource.HTTP)
                .occurredAt(Instant.now()).build();
    }

    @Test
    void publishesAuditRecordedViaSpringEvents() {
        ApplicationEventPublisher spring = mock(ApplicationEventPublisher.class);
        ActionDeliveryChannel delivery = mock(ActionDeliveryChannel.class);
        EvaluationEventPublisher pub = new EvaluationEventPublisher(spring, delivery);

        pub.publishAudit(99L, event(), "PULL", 3, EvalResult.miss(), null, "ROLLOUT");

        ArgumentCaptor<AuditRecorded> captor = ArgumentCaptor.forClass(AuditRecorded.class);
        verify(spring).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(99L);
        assertThat(captor.getValue().mode()).isEqualTo("PULL");
        assertThat(captor.getValue().candidateCount()).isEqualTo(3);
        assertThat(captor.getValue().blockedBy()).isEqualTo("ROLLOUT");
    }

    @Test
    void publishesActionRequestedViaDeliveryChannel() {
        ApplicationEventPublisher spring = mock(ApplicationEventPublisher.class);
        ActionDeliveryChannel delivery = mock(ActionDeliveryChannel.class);
        EvaluationEventPublisher pub = new EvaluationEventPublisher(spring, delivery);
        Decision pass = new Decision("PASS", "", 1, 3L);

        pub.publishActions(7L, 1L, "e1", "s", List.of(pass));

        ArgumentCaptor<ActionRequested> captor = ArgumentCaptor.forClass(ActionRequested.class);
        verify(delivery).deliver(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(7L);
        assertThat(captor.getValue().tenantId()).isEqualTo(1L);
        assertThat(captor.getValue().eventId()).isEqualTo("e1");
        assertThat(captor.getValue().hitDecisions()).containsExactly(pass);
    }
}
