package com.sstlfsj.rule.web.config;

import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.api.service.MetricWriteService.RuleRef;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MetricController 单元测试：create / update / impact 路由与委托。create 的 metricCode 以 query param 传入。 */
class MetricControllerTest {

    private MockMvc mockMvc;
    private MetricWriteService service;

    @BeforeEach
    void setUp() {
        service = mock(MetricWriteService.class);
        JsonMapper mapper = JsonMapper.builder().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MetricController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    // ── POST /api/v1/metrics ──────────────────────────────────────────────────

    @Test
    void create_returns201_andDelegatesService() throws Exception {
        when(service.create(any(), any(), any(), any())).thenReturn(100L);

        mockMvc.perform(post("/api/v1/metrics")
                        .param("tenantId", "1")
                        .param("metricCode", "account.age")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"账龄","sourceType":"ATTRIBUTE","dataType":"LONG",
                             "paramsJson":"{}","cacheTtlSeconds":60,"allowProvided":false}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(100));

        verify(service).create(eq(1L), eq("account.age"), any(MetricWriteCommand.class), eq("dev"));
    }

    @Test
    void create_missingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/metrics")
                        .param("metricCode", "account.age")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /api/v1/metrics/{metricCode} ─────────────────────────────────────

    @Test
    void update_returns200_andDelegatesService() throws Exception {
        when(service.update(any(), any(), any(), anyBoolean(), any())).thenReturn(2);

        mockMvc.perform(put("/api/v1/metrics/account.age")
                        .param("tenantId", "1")
                        .param("breakingChange", "true")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"账龄v2","sourceType":"ATTRIBUTE","dataType":"LONG",
                             "paramsJson":"{}","cacheTtlSeconds":60,"allowProvided":false}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(2));

        verify(service).update(eq(1L), eq("account.age"), any(MetricWriteCommand.class),
                eq(true), eq("dev"));
    }

    @Test
    void update_defaultBreakingChangeFalse() throws Exception {
        when(service.update(any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        mockMvc.perform(put("/api/v1/metrics/account.age")
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"账龄","sourceType":"ATTRIBUTE","dataType":"LONG",
                             "paramsJson":"{}","cacheTtlSeconds":60,"allowProvided":false}
                            """))
                .andExpect(status().isOk());

        // breakingChange 默认 false
        verify(service).update(eq(1L), eq("account.age"), any(MetricWriteCommand.class),
                eq(false), eq("dev"));
    }

    @Test
    void update_illegalArgument_returns400() throws Exception {
        when(service.update(any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("metric 不存在或无 ACTIVE 版本: account.age"));

        mockMvc.perform(put("/api/v1/metrics/account.age")
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"账龄","sourceType":"ATTRIBUTE","dataType":"LONG",
                             "paramsJson":"{}","cacheTtlSeconds":60,"allowProvided":false}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── GET /api/v1/metrics/{metricCode}/versions/{version}/impact ────────────

    @Test
    void impact_returns200_withRuleRefList() throws Exception {
        List<RuleRef> refs = List.of(
                new RuleRef(10L, "risk.transfer", "转账风控", 200L),
                new RuleRef(11L, "risk.login",    "登录风控", 201L)
        );
        when(service.findReferencingRules(1L, "account.age", 1)).thenReturn(refs);

        mockMvc.perform(get("/api/v1/metrics/account.age/versions/1/impact")
                        .param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].ruleCode").value("risk.transfer"))
                .andExpect(jsonPath("$.data[1].ruleName").value("登录风控"));

        verify(service).findReferencingRules(1L, "account.age", 1);
    }

    @Test
    void impact_emptyResult_returns200WithEmptyArray() throws Exception {
        when(service.findReferencingRules(any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/metrics/account.age/versions/1/impact")
                        .param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void impact_missingTenantId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/metrics/account.age/versions/1/impact"))
                .andExpect(status().isBadRequest());
    }
}
