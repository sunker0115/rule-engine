package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 AuditLog Lombok getter/setter 及字段覆盖。 */
class AuditLogTest {

    @Test
    void getterSetter_roundTrip() {
        AuditLog log = new AuditLog();
        log.setId(1L);
        log.setTenantId(100L);
        log.setActor("operator1");
        log.setActorType(ActorType.USER);
        log.setAction("PUBLISH");
        log.setTargetType("rule_definition");
        log.setTargetId("42");
        log.setBeforeSnapshot("{\"status\":\"DRAFT\"}");
        log.setAfterSnapshot("{\"status\":\"PUBLISHED\"}");
        log.setTraceId("trace-abc-123");
        LocalDateTime now = LocalDateTime.now();
        log.setOperatedAt(now);

        assertEquals(1L, log.getId());
        assertEquals(100L, log.getTenantId());
        assertEquals("operator1", log.getActor());
        assertEquals(ActorType.USER, log.getActorType());
        assertEquals("PUBLISH", log.getAction());
        assertEquals("rule_definition", log.getTargetType());
        assertEquals("42", log.getTargetId());
        assertEquals("{\"status\":\"DRAFT\"}", log.getBeforeSnapshot());
        assertEquals("{\"status\":\"PUBLISHED\"}", log.getAfterSnapshot());
        assertEquals("trace-abc-123", log.getTraceId());
        assertEquals(now, log.getOperatedAt());
    }

    @Test
    void defaultValues_areNull() {
        AuditLog log = new AuditLog();
        assertNull(log.getId());
        assertNull(log.getTenantId());
        assertNull(log.getActor());
        assertNull(log.getOperatedAt());
    }
}
