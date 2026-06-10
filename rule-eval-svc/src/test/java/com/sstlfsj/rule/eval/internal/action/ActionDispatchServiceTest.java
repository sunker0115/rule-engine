package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecutedEvent;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ActionDispatchService 单元测试：验证 best-effort 派发、空绑定、handler 缺失、失败仍记录四个场景。 */
class ActionDispatchServiceTest {

    private SceneActionBindingIndex bindingIndex;
    private DomainEventPublisher eventPublisher;
    private ActionHandler stubHandler;
    private ActionDispatchService service;

    @BeforeEach
    void setUp() {
        bindingIndex = mock(SceneActionBindingIndex.class);
        eventPublisher = mock(DomainEventPublisher.class);
        stubHandler = mock(ActionHandler.class);
        when(stubHandler.execute(any())).thenReturn(ActionResult.success("aid", "BLOCK_TRANSACTION"));

        service = new ActionDispatchService(
                Map.of("BLOCK_TRANSACTION", stubHandler),
                bindingIndex,
                eventPublisher);
    }

    @Test
    void dispatch_withBinding_callsHandlerAndPublishesEvent() {
        when(bindingIndex.get(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verify(stubHandler).execute(any(ActionContext.class));
        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecutedEvent ae
                && "BLOCK_TRANSACTION".equals(ae.actionId())
                && "SUCCESS".equals(ae.result().status().name())));
    }

    @Test
    void dispatch_passesDefaultParamsToActionContext() {
        when(bindingIndex.get(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow(
                        "BLOCK_TRANSACTION", Map.of("reason", "risk"))));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        ArgumentCaptor<ActionContext> ctx = ArgumentCaptor.forClass(ActionContext.class);
        verify(stubHandler).execute(ctx.capture());
        assertThat(ctx.getValue().params()).isEqualTo(Map.of("reason", "risk"));
    }

    @Test
    void dispatch_emptyBindings_doesNothing() {
        when(bindingIndex.get(1L, "fraud_check")).thenReturn(List.of());

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void dispatch_handlerNotRegistered_publishesSkippedEvent() {
        when(bindingIndex.get(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("UNKNOWN_ACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecutedEvent ae
                && "UNKNOWN_ACTION".equals(ae.actionType())
                && ae.result().status() == ActionResult.ActionStatus.SKIPPED));
    }

    @Test
    void dispatch_actionId_isDeterministicActionType() {
        when(bindingIndex.get(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecutedEvent ae
                && "BLOCK_TRANSACTION".equals(ae.actionId())));
    }

    @Test
    void dispatch_handlerFailed_stillPublishesEvent() {
        // best-effort：失败不重试不释放，但仍发布 ActionExecutedEvent 落库记录结果
        when(bindingIndex.get(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));
        when(stubHandler.execute(any()))
                .thenReturn(ActionResult.failed("BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "ERR", true));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecutedEvent ae
                && "FAILED".equals(ae.result().status().name())));
    }
}
