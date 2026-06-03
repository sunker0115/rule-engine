package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 DecisionDefinition Lombok getter/setter 及字段覆盖。 */
class DecisionDefinitionTest {

    @Test
    void getterSetter_roundTrip() {
        DecisionDefinition dd = new DecisionDefinition();
        dd.setId(10L);
        dd.setTenantId(100L);
        dd.setCode("REJECT");
        dd.setName("拒绝");
        dd.setPriority(1);
        dd.setDescription("高风险直接拒绝");
        dd.setActions("[{\"actionType\":\"BLOCK\"}]");
        dd.setStatus("ACTIVE");
        dd.setCreatedBy("admin");
        dd.setUpdatedBy("admin2");

        assertEquals(10L, dd.getId());
        assertEquals(100L, dd.getTenantId());
        assertEquals("REJECT", dd.getCode());
        assertEquals("拒绝", dd.getName());
        assertEquals(1, dd.getPriority());
        assertEquals("高风险直接拒绝", dd.getDescription());
        assertEquals("[{\"actionType\":\"BLOCK\"}]", dd.getActions());
        assertEquals("ACTIVE", dd.getStatus());
        assertEquals("admin", dd.getCreatedBy());
        assertEquals("admin2", dd.getUpdatedBy());
    }

    @Test
    void defaultValues_areNull() {
        DecisionDefinition dd = new DecisionDefinition();
        assertNull(dd.getId());
        assertNull(dd.getTenantId());
        assertNull(dd.getCode());
        assertNull(dd.getCreatedAt());
        assertNull(dd.getUpdatedAt());
    }
}
