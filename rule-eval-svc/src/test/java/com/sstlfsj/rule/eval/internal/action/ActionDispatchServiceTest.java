package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.async.ActionExecuted;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ActionDispatchService 单元测试：验证 handler 派发、空绑定、handler 缺失、幂等与失败释放五个场景。 */
class ActionDispatchServiceTest {

    private SceneActionBindingReadMapper bindingMapper;
    private DomainEventPublisher eventPublisher;
    private ActionHandler stubHandler;
    private ActionIdempotencyGuard guard;
    private ActionDispatchService service;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(SceneActionBindingReadMapper.class);
        eventPublisher = mock(DomainEventPublisher.class);
        stubHandler = mock(ActionHandler.class);
        when(stubHandler.execute(any())).thenReturn(ActionResult.success("aid", "BLOCK_TRANSACTION"));
        guard = mock(ActionIdempotencyGuard.class);
        when(guard.claim(any())).thenReturn(true);   // 默认放行；去重用例单独 stub false

        service = new ActionDispatchService(
                Map.of("BLOCK_TRANSACTION", stubHandler),
                bindingMapper,
                eventPublisher,
                guard);
    }

    @Test
    void dispatch_withBinding_callsHandlerAndPublishesEvent() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verify(stubHandler).execute(any(ActionContext.class));
        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecuted ae
                && "BLOCK_TRANSACTION".equals(ae.actionId())
                && "SUCCESS".equals(ae.result().status().name())));
    }

    @Test
    void dispatch_emptyBindings_doesNothing() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check")).thenReturn(List.of());

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void dispatch_handlerNotRegistered_publishesSkippedEvent() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("UNKNOWN_ACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecuted ae
                && "UNKNOWN_ACTION".equals(ae.actionType())
                && ae.result().status() == ActionResult.ActionStatus.SKIPPED));
    }

    @Test
    void dispatch_actionId_isDeterministicActionType() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecuted ae
                && "BLOCK_TRANSACTION".equals(ae.actionId())));
    }

    @Test
    void dispatch_claimRejected_skipsHandlerAndPublish() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));
        when(guard.claim(any())).thenReturn(false);   // 已被占坑（重复 eventId）

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void dispatch_handlerFailed_releasesClaimForRetry() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));
        when(stubHandler.execute(any()))
                .thenReturn(ActionResult.failed("BLOCK_TRANSACTION", "BLOCK_TRANSACTION", "ERR", true));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new Decision("REJECT", "", 10, 1L)));

        verify(guard).release(anyString());
        verify(eventPublisher).publish(argThat(o -> o instanceof ActionExecuted ae
                && "FAILED".equals(ae.result().status().name())));
    }
}
