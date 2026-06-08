package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.async.InProcessAsyncDeliveryChannel;
import com.sstlfsj.rule.eval.internal.async.DispatchActionsCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 验证进程内异步投递：deliver 入队后由后台消费委托 ActionDispatchService（best-effort）。 */
class InProcessAsyncDeliveryChannelTest {

    @Test
    void deliversToDispatchServiceAsync() throws Exception {
        ActionDispatchService svc = mock(ActionDispatchService.class);
        InProcessAsyncDeliveryChannel channel = new InProcessAsyncDeliveryChannel(2000, 200, 50, svc);
        channel.afterPropertiesSet();

        channel.deliver(new DispatchActionsCommand(7L, 1L, "e1", "s", List.of()));

        Thread.sleep(300);   // 等后台消费
        channel.destroy();

        verify(svc, times(1)).dispatch(7L, 1L, "e1", "s", List.of());
    }
}
