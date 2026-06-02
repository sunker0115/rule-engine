package com.sstlfsj.rule.kernel.api.spi.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    @Test
    void schedule_taskIsExecutedByImplementation() {
        List<String> executed = new ArrayList<>();

        Scheduler scheduler = new Scheduler() {
            @Override
            public void schedule(String jobCode, String cronExpression, Runnable task) {
                // Inline execution simulates the scheduler running the task.
                task.run();
            }

            @Override
            public void unschedule(String jobCode) {}
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
        };
        assertDoesNotThrow(() -> scheduler.unschedule("EXPIRE_CHECK"));
    }
}
