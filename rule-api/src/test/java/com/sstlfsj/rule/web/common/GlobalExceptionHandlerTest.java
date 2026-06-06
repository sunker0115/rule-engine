package com.sstlfsj.rule.web.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证 GlobalExceptionHandler 对常见异常的 HTTP 响应映射。 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    /** 触发异常的桩 Controller。 */
    @RestController
    static class StubController {
        @GetMapping("/test/illegal")
        public String illegal() { throw new IllegalArgumentException("参数非法"); }

        @GetMapping("/test/runtime")
        public String runtime() { throw new RuntimeException("内部错误"); }

        @GetMapping("/test/required-param")
        public String requiredParam(@RequestParam String name) { return name; }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void illegalArgument_returns400_withErrorCode() throws Exception {
        mockMvc.perform(get("/test/illegal").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("参数非法"));
    }

    @Test
    void runtimeException_returns500_withErrorCode() throws Exception {
        mockMvc.perform(get("/test/runtime").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    @Test
    void missingRequiredParam_returns400() throws Exception {
        mockMvc.perform(get("/test/required-param").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
