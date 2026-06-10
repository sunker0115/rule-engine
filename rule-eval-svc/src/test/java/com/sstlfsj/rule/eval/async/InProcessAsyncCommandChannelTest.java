package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.async.InProcessAsyncCommandChannel;
import com.sstlfsj.rule.eval.internal.async.DispatchActionsCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 验证进程内异步投递：deliver 入队后由后台消费委托 ActionDispatchService（best-effort）。 */
class InProcessAsyncCommandChannelTest {

    @Test
    void deliversToDispatchServiceAsync() throws Exception {
        ActionDispatchService svc = mock(ActionDispatchService.class);
        InProcessAsyncCommandChannel channel = new InProcessAsyncCommandChannel(2000, 200, 50, svc);
        channel.afterPropertiesSet();

        channel.deliver(new DispatchActionsCommand(7L, 1L, "e1", "s", List.of()));

        Thread.sleep(300);   // 等后台消费
        channel.destroy();

        verify(svc, times(1)).dispatch(7L, 1L, "e1", "s", List.of());
    }

    @Test
    void queueFull_dropsAndCounts_withoutThrowing() throws Exception {
        ActionDispatchService svc = mock(ActionDispatchService.class);
        // capacity=1 + 超长 flush 间隔：消费者短期不排空，灌入超量必触发队列满丢弃
        InProcessAsyncCommandChannel channel = new InProcessAsyncCommandChannel(1, 200, 60_000, svc);
        channel.afterPropertiesSet();
        try {
            for (int i = 0; i < 50; i++) {
                channel.deliver(new DispatchActionsCommand(7L, 1L, "e" + i, "s", List.of()));
            }
            assertThat(channel.droppedCount()).isGreaterThan(0);   // 满后丢弃被计数,不抛
        } finally {
            channel.destroy();
        }
    }
}
