package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionResultTest {

    @Test
    void success_setsCorrectFields() {
        ActionResult r = ActionResult.success("a1", "SEND_MSG");
        assertEquals("a1", r.actionId());
        assertEquals("SEND_MSG", r.actionType());
        assertEquals(ActionResult.ActionStatus.SUCCESS, r.status());
        assertNull(r.errorCode());
        assertFalse(r.retryable());
    }

    @Test
    void skipped_setsReasonAsErrorCode() {
        ActionResult r = ActionResult.skipped("a1", "SEND_MSG", "CONDITION_NOT_MET");
        assertEquals(ActionResult.ActionStatus.SKIPPED, r.status());
        assertEquals("CONDITION_NOT_MET", r.errorCode());
        assertFalse(r.retryable());
    }

    @Test
    void failed_retryable_true() {
        ActionResult r = ActionResult.failed("a1", "SEND_MSG", "DOWNSTREAM_TIMEOUT", true);
        assertEquals(ActionResult.ActionStatus.FAILED, r.status());
        assertEquals("DOWNSTREAM_TIMEOUT", r.errorCode());
        assertTrue(r.retryable());
    }

    @Test
    void failed_retryable_false() {
        ActionResult r = ActionResult.failed("a1", "SEND_MSG", "INVALID_PARAM", false);
        assertFalse(r.retryable());
    }

    @Test
    void notSupported_hasNullIds() {
        ActionResult r = ActionResult.notSupported();
        assertNull(r.actionId());
        assertNull(r.actionType());
        assertEquals(ActionResult.ActionStatus.SKIPPED, r.status());
        assertEquals("COMPENSATE_NOT_SUPPORTED", r.errorCode());
    }
}
