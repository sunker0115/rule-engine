package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 OutcomeService 是公开接口且具有预期方法签名 + OutcomeRecord 记录形状。 */
class OutcomeServiceTest {

    @Test
    void isInterface() {
        assertTrue(OutcomeService.class.isInterface());
    }

    @Test
    void hasRecordOutcomesMethod() throws NoSuchMethodException {
        var method = OutcomeService.class.getMethod("recordOutcomes", Long.class, List.class);
        assertEquals(int.class, method.getReturnType());
    }

    @Test
    void outcomeRecord_carriesAllFields() {
        Instant t = Instant.parse("2026-06-18T10:00:00Z");
        OutcomeRecord r = new OutcomeRecord("evt-1", "FRAUD", new BigDecimal("1280.50"), t, "ops", "note");
        assertEquals("evt-1", r.eventId());
        assertEquals("FRAUD", r.outcomeLabel());
        assertEquals(new BigDecimal("1280.50"), r.outcomeValue());
        assertEquals(t, r.labeledAt());
        assertEquals("ops", r.source());
        assertEquals("note", r.note());
    }
}
