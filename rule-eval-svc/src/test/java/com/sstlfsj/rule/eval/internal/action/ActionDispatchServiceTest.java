package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecutedEvent;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionAction;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ActionDispatchService 单元测试：D27 派发 finalDecision.actions（best-effort），handler 缺失/失败/空决策场景。 */
class ActionDispatchServiceTest {

    private DomainEventPublisher eventPublisher;
    private ActionHandler stubHandler;
    private ActionDispatchService service;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(DomainEventPublisher.class);
        stubHandler = mock(ActionHandler.class);
        when(stubHandler.execute(any())).thenReturn(ActionResult.success("a1", "SEND_ALERT"));
        service = new ActionDispatchService(Map.of("SEND_ALERT", stubHandler), eventPublisher);
    }

    private static Decision decisionWith(DecisionAction... actions) {
        return new Decision("REJECT", "拒绝", 10, 1L, null, 0L, null, List.of(actions));
    }

    @Test
    void dispatch_finalDecisionActions_callsHandlerAndPublishesEvent() {
        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                decisionWith(new DecisionAction("a1", "SEND_ALERT", 0, Map.of())));

        verify(stubHandler).execute(any(ActionContext.class));
        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecutedEvent ae
                && "a1".equals(ae.actionId())
                && "REJECT".equals(ae.decisionCode())
                && "SUCCESS".equals(ae.result().status().name())));
    }

    @Test
    void dispatch_paramsFromDecisionAction() {
        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                decisionWith(new DecisionAction("a1", "SEND_ALERT", 0, Map.of("reason", "risk"))));

        ArgumentCaptor<ActionContext> ctx = ArgumentCaptor.forClass(ActionContext.class);
        verify(stubHandler).execute(ctx.capture());
        assertThat(ctx.getValue().params()).isEqualTo(Map.of("reason", "risk"));
    }

    @Test
    void dispatch_nullFinalDecision_doesNothing() {
        service.dispatch(42L, 1L, "evt-001", "fraud_check", null);
        verifyNoInteractions(stubHandler);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void dispatch_noActions_doesNothing() {
        service.dispatch(42L, 1L, "evt-001", "fraud_check", decisionWith());
        verifyNoInteractions(stubHandler);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void dispatch_handlerNotRegistered_publishesSkippedEvent() {
        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                decisionWith(new DecisionAction("a1", "UNKNOWN_ACTION", 0, Map.of())));

        verifyNoInteractions(stubHandler);
        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecutedEvent ae
                && "UNKNOWN_ACTION".equals(ae.actionType())
                && ae.result().status() == ActionResult.ActionStatus.SKIPPED));
    }

    @Test
    void dispatch_handlerFailed_stillPublishesEvent() {
        // best-effort：失败不重试，但仍发布 ActionExecutedEvent 落库记录结果
        when(stubHandler.execute(any()))
                .thenReturn(ActionResult.failed("a1", "SEND_ALERT", "ERR", false));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                decisionWith(new DecisionAction("a1", "SEND_ALERT", 0, Map.of())));

        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecutedEvent ae
                && "FAILED".equals(ae.result().status().name())));
    }
}
