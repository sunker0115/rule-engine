package com.sstlfsj.rule.web.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    void ok_setsSuccessTrueAndData() {
        ApiResponse<String> resp = ApiResponse.ok("hello");
        assertTrue(resp.success());
        assertEquals("hello", resp.data());
        assertNull(resp.errorCode());
        assertNull(resp.message());
    }

    @Test
    void ok_acceptsNull() {
        ApiResponse<Void> resp = ApiResponse.ok(null);
        assertTrue(resp.success());
        assertNull(resp.data());
    }

    @Test
    void error_setsSuccessFalseAndCodes() {
        ApiResponse<Object> resp = ApiResponse.error("RULE_NOT_FOUND", "规则不存在");
        assertFalse(resp.success());
        assertNull(resp.data());
        assertEquals("RULE_NOT_FOUND", resp.errorCode());
        assertEquals("规则不存在", resp.message());
    }
}
