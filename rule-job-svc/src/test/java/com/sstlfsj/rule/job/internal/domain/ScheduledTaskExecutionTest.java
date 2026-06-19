package com.sstlfsj.rule.job.internal.domain;

import com.sstlfsj.rule.job.api.TaskExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledTaskExecutionTest {
    @Test
    void settersAndGetters_roundTrip() {
        LocalDateTime now = LocalDateTime.now();
        ScheduledTaskExecution e = new ScheduledTaskExecution();
        e.setScheduledTaskId(7L);
        e.setTenantId(1L);
        e.setTriggerAt(now);
        e.setStatus(TaskExecutionStatus.PARTIAL_FAIL);
        e.setProcessedCount(10);
        e.setSuccessCount(8);
        e.setErrorCount(2);
        e.setErrorSummary("2 failed");
        assertEquals(7L, e.getScheduledTaskId());
        assertEquals(TaskExecutionStatus.PARTIAL_FAIL, e.getStatus());
        assertEquals(10, e.getProcessedCount());
        assertEquals(8, e.getSuccessCount());
        assertEquals(2, e.getErrorCount());
        assertEquals("2 failed", e.getErrorSummary());
        assertEquals(now, e.getTriggerAt());
    }
}
