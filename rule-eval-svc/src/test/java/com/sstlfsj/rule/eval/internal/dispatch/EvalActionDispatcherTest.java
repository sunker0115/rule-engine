package com.sstlfsj.rule.eval.internal.dispatch;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EvalActionDispatcherTest {

    private RuleEvent event(String id) {
        return new RuleEvent("1", "test_scene", "TEST_EVENT", "u1",
                id, Instant.now(), Map.of(), Map.of());
    }

    @Test
    void submit_returnsTrue_whenQueueHasCapacity() throws InterruptedException {
        // 队列有容量时 submit 返回 true，且消费者最终会执行该事件
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);

        EvalActionDispatcher dispatcher = new EvalActionDispatcher(100, e -> {
            callCount.incrementAndGet();
            latch.countDown();
        });
        dispatcher.start();

        boolean accepted = dispatcher.submit(event("evt-001"));
        assertTrue(accepted, "队列有容量时 submit 应返回 true");

        // 等待消费者处理
        boolean processed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(processed, "消费者应在 2 秒内处理事件");
        assertEquals(1, callCount.get());

        dispatcher.stop();
    }

    @Test
    void submit_returnsFalse_whenQueueFull() {
        // 队列满时 submit 返回 false（背压信号）
        // 消费者函数故意阻塞，使队列无法被消费
        EvalActionDispatcher dispatcher = new EvalActionDispatcher(2, e -> {
            try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
        });
        dispatcher.start();

        // 填满队列（消费者被第一个事件阻塞后，队列容量为 2，再投 2 个填满）
        dispatcher.submit(event("evt-fill-1")); // 被消费者取走阻塞
        // 稍等消费者取走第一个
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        dispatcher.submit(event("evt-fill-2")); // 占位
        dispatcher.submit(event("evt-fill-3")); // 占位，此时队列已满

        // 再投一个，应返回 false
        boolean accepted = dispatcher.submit(event("evt-overflow"));
        assertFalse(accepted, "队列满时 submit 应返回 false");

        dispatcher.stop();
    }

    @Test
    void stop_gracefullyDrainsQueue() throws InterruptedException {
        // stop() 前已投递的事件都应被处理
        int total = 5;
        CopyOnWriteArrayList<String> processed = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(total);

        EvalActionDispatcher dispatcher = new EvalActionDispatcher(100, e -> {
            processed.add(e.eventId());
            latch.countDown();
        });
        dispatcher.start();

        for (int i = 0; i < total; i++) {
            dispatcher.submit(event("evt-" + i));
        }

        // 等待所有事件被消费
        boolean allProcessed = latch.await(3, TimeUnit.SECONDS);
        dispatcher.stop();

        assertTrue(allProcessed, "stop 前投递的 " + total + " 个事件应全部被处理");
        assertEquals(total, processed.size());
    }
}
