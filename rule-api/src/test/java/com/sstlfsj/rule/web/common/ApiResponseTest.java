package com.sstlfsj.rule.web.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    void ok_setsSuccessTrueAndData() {
        ApiResponse<String> resp = ApiResponse.ok("hello");
        assertTrue(resp.success());
        assertEquals("hello", resp.data());
    }

    @Test
    void ok_acceptsNull() {
        ApiResponse<Void> resp = ApiResponse.ok(null);
        assertTrue(resp.success());
        assertNull(resp.data());
    }
}
