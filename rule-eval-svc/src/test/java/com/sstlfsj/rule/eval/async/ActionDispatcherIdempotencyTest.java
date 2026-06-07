package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.async.ActionDispatcher;
import com.sstlfsj.rule.eval.internal.async.ActionRequested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 验证 ActionDispatcher 委托 ActionDispatchService（at-least-once：重投即多次调用，幂等在 handler）。 */
class ActionDispatcherIdempotencyTest {

    @Test
    void delegatesToDispatchServicePerDelivery() {
        ActionDispatchService svc = mock(ActionDispatchService.class);
        ActionDispatcher dispatcher = new ActionDispatcher(svc);
        ActionRequested e = new ActionRequested(7L, 1L, "e1", "s", List.of());

        dispatcher.on(e);
        dispatcher.on(e);   // 重投

        verify(svc, times(2)).dispatch(7L, 1L, "e1", "s", List.of());
    }
}
