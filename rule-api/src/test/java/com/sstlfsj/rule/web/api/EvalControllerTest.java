package com.sstlfsj.rule.web.api;

import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.api.service.TenantQueryService;
import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import com.sstlfsj.rule.web.mask.SensitiveRefsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EvalControllerTest {

    private MockMvc mockMvc;
    private EvalService evalService;
    private TenantQueryService tenantQueryService;
    private SensitiveRefsResolver sensitiveRefsResolver;

    private static final String EVENT_JSON = """
            {"tenantCode":"acme","sceneCode":"PAYMENT","eventType":"ORDER",
             "subjectId":"u1","eventId":"evt-1","occurredAt":null,
             "payload":{}}
            """;

    @BeforeEach
    void setUp() {
        evalService = mock(EvalService.class);
        tenantQueryService = mock(TenantQueryService.class);
        sensitiveRefsResolver = mock(SensitiveRefsResolver.class);
        when(tenantQueryService.resolveIdByCode("acme")).thenReturn(9001L);
        mockMvc = MockMvcBuilders.standaloneSetup(new EvalController(evalService, tenantQueryService, sensitiveRefsResolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void pushEvent_returns202_whenAccepted() throws Exception {
        when(evalService.acceptEvent(any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/rule/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(true));
    }

    @Test
    void pushEvent_injectsHttpSource() throws Exception {
        // 渠道由 controller 权威设为 HTTP，不依赖请求体携带
        when(evalService.acceptEvent(any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/rule/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isAccepted());

        ArgumentCaptor<RuleEvent> captor = ArgumentCaptor.forClass(RuleEvent.class);
        verify(evalService).acceptEvent(captor.capture());
        assertThat(captor.getValue().source()).isEqualTo(EventSource.HTTP);
        assertThat(captor.getValue().eventId()).isEqualTo("evt-1");
        // 边界把租户 code "acme" 解析为内部 id "9001"，引擎只见数字 id
        assertThat(captor.getValue().tenantId()).isEqualTo("9001");
    }

    @Test
    void evaluate_returns400_whenTenantCodeUnknown() throws Exception {
        // 未知租户 code → 解析返回 null → 400，不进引擎
        when(tenantQueryService.resolveIdByCode("ghost")).thenReturn(null);
        String badJson = """
                {"tenantCode":"ghost","sceneCode":"PAYMENT","eventType":"ORDER",
                 "subjectId":"u1","eventId":"evt-1","occurredAt":null,
                 "payload":{}}
                """;

        mockMvc.perform(post("/api/v1/rule/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(evalService);
    }

    @Test
    void evaluate_returns200_withResult() throws Exception {
        when(evalService.evaluate(any(), any())).thenReturn(EvalResult.miss());

        mockMvc.perform(post("/api/v1/rule/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void evaluate_passesAsOfThroughToService() throws Exception {
        // 请求体携带 asOf（ISO-8601）→ controller 透传给 evalService.evaluate 的 asOf 入参
        when(evalService.evaluate(any(), any())).thenReturn(EvalResult.miss());
        String jsonWithAsOf = """
                {"tenantCode":"acme","sceneCode":"PAYMENT","eventType":"ORDER",
                 "subjectId":"u1","eventId":"evt-1","occurredAt":null,
                 "payload":{},"asOf":"2020-01-01T00:00:00Z"}
                """;

        mockMvc.perform(post("/api/v1/rule/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithAsOf))
                .andExpect(status().isOk());

        ArgumentCaptor<java.time.Instant> asOfCaptor = ArgumentCaptor.forClass(java.time.Instant.class);
        verify(evalService).evaluate(any(RuleEvent.class), asOfCaptor.capture());
        assertThat(asOfCaptor.getValue()).isEqualTo(java.time.Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void evaluate_nullAsOf_whenOmitted() throws Exception {
        // 请求体不带 asOf → controller 传 null（引擎降级用 Instant.now()）
        when(evalService.evaluate(any(), any())).thenReturn(EvalResult.miss());

        mockMvc.perform(post("/api/v1/rule/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isOk());

        ArgumentCaptor<java.time.Instant> asOfCaptor = ArgumentCaptor.forClass(java.time.Instant.class);
        verify(evalService).evaluate(any(RuleEvent.class), asOfCaptor.capture());
        assertThat(asOfCaptor.getValue()).isNull();
    }

    @Test
    void dryRun_returns200_withResult() throws Exception {
        when(evalService.dryRun(any(), isNull(), eq(1L))).thenReturn(EvalResult.miss());

        mockMvc.perform(post("/api/v1/rule/dry-run?ruleVersionId=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void dryRun_masksSensitivePayloadLeaf() throws Exception {
        // 敏感 payload 字段 phone 的叶子 actualValue 在 dry-run 出口被脱敏为 "***"，非敏感 amount 保留原值
        when(sensitiveRefsResolver.forScene(9001L, "PAYMENT"))
                .thenReturn(new SceneService.SensitiveRefs(java.util.Set.of("phone"), java.util.Set.of()));
        NodeTrace sensitiveLeaf = new NodeTrace(
                "ConditionNode", "EQ", "phone", true, "13800001111", "PAYLOAD",
                null, java.util.List.of(), 1L, "ruleA", 1L, null, null);
        NodeTrace plainLeaf = new NodeTrace(
                "ConditionNode", "GT", "amount", true, "100", "PAYLOAD",
                null, java.util.List.of(), 1L, "ruleA", 1L, null, null);
        when(evalService.dryRun(any(), isNull(), eq(1L)))
                .thenReturn(new EvalResult(true, null, java.util.List.of(),
                        java.util.List.of(sensitiveLeaf, plainLeaf), null, null, null, null));

        mockMvc.perform(post("/api/v1/rule/dry-run?ruleVersionId=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodeTrace[0].actualValue").value("***"))
                .andExpect(jsonPath("$.data.nodeTrace[1].actualValue").value("100"));
    }

    @Test
    void dryRun_missingTarget_returns400() throws Exception {
        // 不带 ruleId / ruleVersionId：service 抛 IllegalArgumentException → GlobalExceptionHandler 映射 400
        when(evalService.dryRun(any(), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("MISSING_DRYRUN_TARGET: 必须指定 ruleId 或 ruleVersionId"));

        mockMvc.perform(post("/api/v1/rule/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void evaluate_missingRequiredField_returns400() throws Exception {
        // @Valid + @NotBlank：tenantCode 为 null → 400，在 toEvent 解析前被 Bean Validation 拦截（或拦截未知租户时同样 400）
        String noTenantCode = """
                {"sceneCode":"PAYMENT","eventType":"ORDER",
                 "subjectId":"u1","eventId":"evt-1","payload":{}}
                """;
        mockMvc.perform(post("/api/v1/rule/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noTenantCode))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
        verifyNoInteractions(evalService);
    }
}
