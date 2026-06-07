package com.sstlfsj.rule.web.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.web.api.EvalController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void runtimeException_returns500_withErrorCode_andLogsRequestPath() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        mockMvc.perform(get("/test/runtime").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));

        // 兜底日志应带请求方法与路径，便于定位
        assertThat(appender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("/test/runtime"));
    }

    @Test
    void missingRequiredParam_returns400() throws Exception {
        mockMvc.perform(get("/test/required-param").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }

    @Test
    void noResourceFound_returns404_withErrorCode() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/v1/rules", "/api/v1/rules");
        ApiResponse<Void> resp = new GlobalExceptionHandler().handleNotFound(ex);
        assertThat(resp.success()).isFalse();
        assertThat(resp.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void malformedBody_returns400_notReadable() throws Exception {
        MockMvc evalMvc = MockMvcBuilders
                .standaloneSetup(new EvalController(mock(EvalService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        String badJson = "{\"tenantId\":\"1\",\"occurredAt\":\"not-a-timestamp\"}";
        evalMvc.perform(post("/api/v1/rule/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
