package com.sstlfsj.rule.web.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
import com.sstlfsj.rule.web.mask.SensitiveRefsResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证 GlobalExceptionHandler 对常见异常的 ProblemDetail (RFC 9457) 响应映射。 */
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

        @GetMapping("/test/typed-param")
        public String typedParam(@RequestParam Long tenantId) { return String.valueOf(tenantId); }

        @GetMapping("/test/api-exception")
        public String apiException() {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IMPORT_CONFLICT", "import conflict");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StubController())
                .setMessageConverters(TestMessageConverters.problemDetailConverter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void illegalArgument_returns400_withProblemDetail() throws Exception {
        mockMvc.perform(get("/test/illegal").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("非法参数"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("参数非法"))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.instance").value("/test/illegal"));
    }

    @Test
    void runtimeException_returns500_withProblemDetail_andLogsRequestPath() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        mockMvc.perform(get("/test/runtime").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("服务内部错误"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));

        assertThat(appender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("/test/runtime"));
    }

    @Test
    void missingRequiredParam_returns400() throws Exception {
        mockMvc.perform(get("/test/required-param").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }

    @Test
    void typeMismatchParam_returns400_notInternalError() throws Exception {
        mockMvc.perform(get("/test/typed-param").param("tenantId", "acme").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("tenantId")));
    }

    @Test
    void noResourceFound_returns404_withProblemDetail() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/v1/rules", "/api/v1/rules");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/rules");
        ResponseEntity<ProblemDetail> resp = new GlobalExceptionHandler().handleNotFound(ex, request);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        ProblemDetail pd = resp.getBody();
        assertThat(pd).isNotNull();
        assertThat(pd.getProperties().get("errorCode")).isEqualTo("NOT_FOUND");
        assertThat(pd.getTitle()).isEqualTo("接口不存在");
    }

    @Test
    void apiException_returnsStatusAndErrorCode_problemDetail() throws Exception {
        mockMvc.perform(get("/test/api-exception").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("业务错误"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("import conflict"))
                .andExpect(jsonPath("$.errorCode").value("IMPORT_CONFLICT"))
                .andExpect(jsonPath("$.instance").value("/test/api-exception"));
    }

    @Test
    void malformedBody_returns400_problemDetail() throws Exception {
        MockMvc evalMvc = MockMvcBuilders
                .standaloneSetup(new EvalController(mock(EvalService.class),
                        mock(com.sstlfsj.rule.config.api.service.TenantQueryService.class),
                        mock(SensitiveRefsResolver.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        String badJson = "{\"tenantId\":\"1\",\"occurredAt\":\"not-a-timestamp\"}";
        evalMvc.perform(post("/api/v1/rule/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
