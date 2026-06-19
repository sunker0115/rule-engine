package com.sstlfsj.rule.job.internal.domain;

import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.api.TriggerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledTaskTest {
    @Test
    void settersAndGetters_roundTrip() {
        ScheduledTask t = new ScheduledTask();
        t.setTenantId(1L);
        t.setCode("c");
        t.setTaskType(TaskType.TRIGGER);
        t.setCron("0 0 * * * *");
        t.setConfig(new TriggerConfig("s", "e", null));
        t.setCursor("2026-06-18T10:00:00Z");
        t.setStatus(TaskStatus.ACTIVE);
        assertEquals(TaskType.TRIGGER, t.getTaskType());
        assertEquals(TaskStatus.ACTIVE, t.getStatus());
        assertEquals("s", ((TriggerConfig) t.getConfig()).sceneCode());
        assertEquals("2026-06-18T10:00:00Z", t.getCursor());
    }
}
