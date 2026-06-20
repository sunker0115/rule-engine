package com.sstlfsj.rule.kernel.api.spi.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    @Test
    void schedule_taskIsExecutedByImplementation() {
        List<String> executed = new ArrayList<>();

        Scheduler scheduler = new Scheduler() {
            @Override
            public void schedule(String jobCode, String cronExpression, Runnable task) {
                // 内联执行模拟调度器触发任务。
                task.run();
            }

            @Override
            public void unschedule(String jobCode) {}

            @Override
            public void scheduleBroadcast(String code, Consumer<String> onEachNode) {}

            @Override
            public void triggerBroadcast(String code, String param) {}
        };

        scheduler.schedule("EXPIRE_CHECK", "0 * * * * *", () -> executed.add("ran"));

        assertEquals(1, executed.size());
        assertEquals("ran", executed.get(0));
    }

    @Test
    void unschedule_canBeCalledWithoutError() {
        Scheduler scheduler = new Scheduler() {
            @Override
            public void schedule(String jobCode, String cronExpression, Runnable task) {}

            @Override
            public void unschedule(String jobCode) {}

            @Override
            public void scheduleBroadcast(String code, Consumer<String> onEachNode) {}

            @Override
            public void triggerBroadcast(String code, String param) {}
        };
        assertDoesNotThrow(() -> scheduler.unschedule("EXPIRE_CHECK"));
    }

    @Test
    void broadcastContract_recordsRegistrationAndTrigger() {
        Map<String, Consumer<String>> handlers = new HashMap<>();
        List<String> triggered = new ArrayList<>();
        Scheduler scheduler = new Scheduler() {
            @Override
            public void schedule(String c, String cron, Runnable t) {}

            @Override
            public void unschedule(String c) {}

            @Override
            public void scheduleBroadcast(String c, Consumer<String> h) {
                handlers.put(c, h);
            }

            @Override
            public void triggerBroadcast(String c, String param) {
                handlers.get(c).accept(param);
                triggered.add(c + "=" + param);
            }
        };
        scheduler.scheduleBroadcast("config-change", p -> triggered.add("handled:" + p));
        scheduler.triggerBroadcast("config-change", "scene:9100:fraud_check:true");
        assertThat(triggered).containsExactly(
                "handled:scene:9100:fraud_check:true",
                "config-change=scene:9100:fraud_check:true");
    }
}
