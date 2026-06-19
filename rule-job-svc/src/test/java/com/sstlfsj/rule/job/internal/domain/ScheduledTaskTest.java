package com.sstlfsj.rule.job.internal.domain;

import com.sstlfsj.rule.job.api.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledTaskTest {
    @Test
    void settersAndGetters_roundTrip() {
        ScheduledTask t = new ScheduledTask();
        t.setTenantId(1L);
        t.setCode("c");
        t.setTaskType("TRIGGER");
        t.setCron("0 0 * * * *");
        t.setConfig("{\"sceneCode\":\"s\",\"eventType\":\"e\"}");
        t.setRunCursor("2026-06-18T10:00:00Z");
        t.setStatus(TaskStatus.ACTIVE);
        assertEquals("TRIGGER", t.getTaskType());
        assertEquals(TaskStatus.ACTIVE, t.getStatus());
        assertEquals("{\"sceneCode\":\"s\",\"eventType\":\"e\"}", t.getConfig());
        assertEquals("2026-06-18T10:00:00Z", t.getRunCursor());
    }
}
