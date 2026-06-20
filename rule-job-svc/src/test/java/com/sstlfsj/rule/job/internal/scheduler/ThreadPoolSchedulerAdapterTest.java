package com.sstlfsj.rule.job.internal.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadPoolSchedulerAdapterTest {

    private final ThreadPoolSchedulerAdapter adapter = new ThreadPoolSchedulerAdapter();

    @AfterEach
    void tearDown() {
        adapter.close();
    }

    @Test
    void scheduledTaskFiresOnCron() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        // 每秒触发的 6 段 cron，3 秒内至少触发一次
        adapter.schedule("c1", "* * * * * *", latch::countDown);
        assertTrue(latch.await(3, TimeUnit.SECONDS), "task should fire within cron period");
    }

    @Test
    void unscheduleExistingDoesNotThrow() {
        adapter.schedule("c2", "* * * * * *", () -> {});
        assertDoesNotThrow(() -> adapter.unschedule("c2"));
    }

    @Test
    void unscheduleUnknownDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.unschedule("nope"));
    }

    @Test
    void rescheduleReplacesPreviousTrigger() throws InterruptedException {
        AtomicInteger first = new AtomicInteger();
        adapter.schedule("c3", "* * * * * *", first::incrementAndGet);
        CountDownLatch secondRan = new CountDownLatch(1);
        adapter.schedule("c3", "* * * * * *", secondRan::countDown);
        assertTrue(secondRan.await(3, TimeUnit.SECONDS), "replacement task should fire");
    }

    @Test
    void broadcast_localDirectInvokeWithParam() {
        List<String> received = new ArrayList<>();
        adapter.scheduleBroadcast("config-change", received::add);
        adapter.triggerBroadcast("config-change", "scene:9100:fraud_check:true");
        assertThat(received).containsExactly("scene:9100:fraud_check:true");
    }

    @Test
    void broadcast_triggerUnknownCode_noop() {
        adapter.triggerBroadcast("never-registered", "x");
    }
}
