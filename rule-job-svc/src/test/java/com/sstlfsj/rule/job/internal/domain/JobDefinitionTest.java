package com.sstlfsj.rule.job.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobDefinitionTest {

    @Test
    void settersAndGetters_roundTrip() {
        JobDefinition j = new JobDefinition();
        j.setTenantId(1L);
        j.setSceneCode("fraud_check");
        j.setCode("demo-daily");
        j.setName("演示每日扫描");
        j.setCronExpression("0 0 3 * * *");
        j.setSubjectQuery("{\"type\":\"BEAN_METHOD\",\"ref\":\"X#m\"}");
        j.setEventType("login");
        j.setStatus(JobStatus.ACTIVE);

        assertEquals(1L, j.getTenantId());
        assertEquals("fraud_check", j.getSceneCode());
        assertEquals("demo-daily", j.getCode());
        assertEquals("0 0 3 * * *", j.getCronExpression());
        assertEquals("{\"type\":\"BEAN_METHOD\",\"ref\":\"X#m\"}", j.getSubjectQuery());
        assertEquals("login", j.getEventType());
        assertEquals(JobStatus.ACTIVE, j.getStatus());
    }

    // 锁定 D49 死列 payload_template 已从实体移除（防误加回）
    @Test
    void payloadTemplate_fieldRemoved() {
        assertThrows(NoSuchFieldException.class,
                () -> JobDefinition.class.getDeclaredField("payloadTemplate"));
    }
}
