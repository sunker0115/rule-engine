package com.sstlfsj.rule.web.admin;

import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.api.service.MetricWriteService.RuleRef;
import com.sstlfsj.rule.config.api.dto.MetricListItemVO;
import com.sstlfsj.rule.eval.api.FetchTrace;
import com.sstlfsj.rule.eval.api.service.MetricFetchTestService;
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
    private MetadataService metadataService;
    private MetricFetchTestService testService;

    @BeforeEach
    void setUp() {
        service = mock(MetricWriteService.class);
        metadataService = mock(MetadataService.class);
        testService = mock(MetricFetchTestService.class);
        JsonMapper mapper = JsonMapper.builder().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MetricController(service, metadataService, testService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    @Test
    void listMetrics_returns200_withDefinitions() throws Exception {
        // listMetrics 端点委托 listMetricItems(tenantId) → List<MetricListItemVO>
        when(metadataService.listMetricItems(1L)).thenReturn(List.of(
                new MetricListItemVO("account.age", 1, "ATTRIBUTE", "LONG", false, 60, java.util.Map.of(),
                        "账龄", "ACTIVE", 1L, null, null)));

        mockMvc.perform(get("/admin/v1/metrics").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].metricCode").value("account.age"))
                .andExpect(jsonPath("$.data[0].dataType").value("LONG"));

        verify(metadataService).listMetricItems(1L);
    }

    // ── GET /admin/v1/metrics/{metricCode} ──────────────────────────────────────

    @Test
    void getMetric_returns200_withFullDefinition() throws Exception {
        when(metadataService.getMetricItem(1L, "account.age")).thenReturn(
                new MetricListItemVO("account.age", 2, "ATTRIBUTE", "LONG", false, 60,
                        java.util.Map.of("window", "30d"), "账龄", "ACTIVE", 1L, null, null));

        mockMvc.perform(get("/admin/v1/metrics/account.age").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.metricCode").value("account.age"))
                .andExpect(jsonPath("$.data.metricVersion").value(2))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.params.window").value("30d"));

        verify(metadataService).getMetricItem(1L, "account.age");
    }

    @Test
    void getMetric_missing_returns400() throws Exception {
        when(metadataService.getMetricItem(1L, "nope"))
                .thenThrow(new IllegalArgumentException("Metric 不存在: nope"));

        mockMvc.perform(get("/admin/v1/metrics/nope").param("tenantId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── POST /admin/v1/metrics ──────────────────────────────────────────────────

    @Test
    void create_returns201_andDelegatesService() throws Exception {
        when(service.create(any(), any(), any(), any())).thenReturn(100L);

        mockMvc.perform(post("/admin/v1/metrics")
                        .param("tenantId", "1")
                        .param("metricCode", "account.age")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"账龄","sourceType":"ATTRIBUTE","dataType":"LONG",
                             "params":{},"cacheTtlSeconds":60,"allowProvided":false}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(100));

        verify(service).create(eq(1L), eq("account.age"), any(MetricWriteCommand.class), eq("dev"));
    }

    @Test
    void create_missingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/admin/v1/metrics")
                        .param("metricCode", "account.age")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /admin/v1/metrics/{metricCode} ─────────────────────────────────────

    @Test
    void update_returns200_andDelegatesService() throws Exception {
        when(service.update(any(), any(), any(), anyBoolean(), any())).thenReturn(2);

        mockMvc.perform(put("/admin/v1/metrics/account.age")
                        .param("tenantId", "1")
                        .param("breakingChange", "true")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"账龄v2","sourceType":"ATTRIBUTE","dataType":"LONG",
                             "params":{},"cacheTtlSeconds":60,"allowProvided":false}
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

        mockMvc.perform(put("/admin/v1/metrics/account.age")
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"账龄","sourceType":"ATTRIBUTE","dataType":"LONG",
                             "params":{},"cacheTtlSeconds":60,"allowProvided":false}
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

        mockMvc.perform(put("/admin/v1/metrics/account.age")
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"账龄","sourceType":"ATTRIBUTE","dataType":"LONG",
                             "params":{},"cacheTtlSeconds":60,"allowProvided":false}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── GET /admin/v1/metrics/{metricCode}/versions/{version}/impact ────────────

    @Test
    void impact_returns200_withImpactResponse() throws Exception {
        // RuleRef 新字段：ruleDefinitionId, ruleCode, ruleName, sceneCode, status
        List<RuleRef> refs = List.of(
                new RuleRef(10L, "risk.transfer", "转账风控", "risk.transfer", "ACTIVE"),
                new RuleRef(11L, "risk.login",    "登录风控", "risk.login",    "DISABLED")
        );
        when(service.findReferencingRules(1L, "account.age", 1)).thenReturn(refs);

        mockMvc.perform(get("/admin/v1/metrics/account.age/versions/1/impact")
                        .param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 外层 ImpactResponse 包装字段
                .andExpect(jsonPath("$.data.metricCode").value("account.age"))
                .andExpect(jsonPath("$.data.metricVersion").value(1))
                .andExpect(jsonPath("$.data.affectedRuleCount").value(2))
                // affectedRules 数组内容
                .andExpect(jsonPath("$.data.affectedRules").isArray())
                .andExpect(jsonPath("$.data.affectedRules.length()").value(2))
                .andExpect(jsonPath("$.data.affectedRules[0].ruleCode").value("risk.transfer"))
                .andExpect(jsonPath("$.data.affectedRules[0].sceneCode").value("risk.transfer"))
                .andExpect(jsonPath("$.data.affectedRules[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.affectedRules[1].ruleName").value("登录风控"))
                .andExpect(jsonPath("$.data.affectedRules[1].status").value("DISABLED"));

        verify(service).findReferencingRules(1L, "account.age", 1);
    }

    @Test
    void impact_emptyResult_returns200WithEmptyAffectedRules() throws Exception {
        when(service.findReferencingRules(any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/v1/metrics/account.age/versions/1/impact")
                        .param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metricCode").value("account.age"))
                .andExpect(jsonPath("$.data.metricVersion").value(1))
                .andExpect(jsonPath("$.data.affectedRuleCount").value(0))
                .andExpect(jsonPath("$.data.affectedRules").isArray())
                .andExpect(jsonPath("$.data.affectedRules.length()").value(0));
    }

    @Test
    void impact_missingTenantId_returns400() throws Exception {
        mockMvc.perform(get("/admin/v1/metrics/account.age/versions/1/impact"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /admin/v1/metrics/{metricCode}:test ────────────────────────────────

    @Test
    void test_colonRoute_hitsMethod_andReturnsTrace() throws Exception {
        // 验收门：冒号风格路由 :test 真命中方法（不 404），返回分阶段 FetchTrace
        when(testService.test(eq(1L), eq("account.age"), any(), any(), eq("s1")))
                .thenReturn(new FetchTrace("EXTERNAL_HTTP", "GET https://risk/s/9",
                        null, "{\"ok\":true,\"v\":42}", true, 42, null));

        mockMvc.perform(post("/admin/v1/metrics/account.age:test")
                        .param("tenantId", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"sampleVars":{"x":1},"samplePayload":{"id":9},"sampleSubjectId":"s1"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sourceType").value("EXTERNAL_HTTP"))
                .andExpect(jsonPath("$.data.renderedRequest").value("GET https://risk/s/9"))
                .andExpect(jsonPath("$.data.successMatched").value(true))
                .andExpect(jsonPath("$.data.mappedValue").value(42));

        verify(testService).test(eq(1L), eq("account.age"), any(), any(), eq("s1"));
    }

    // ── GET /admin/v1/metrics/usage-counts ──────────────────────────────────────

    @Test
    void usageCounts_returns200() throws Exception {
        when(service.countRuleUsages(1L)).thenReturn(
                List.of(new com.sstlfsj.rule.config.api.service.UsageCount("account.age", 5)));
        mockMvc.perform(get("/admin/v1/metrics/usage-counts").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("account.age"))
                .andExpect(jsonPath("$.data[0].count").value(5));
    }

    // ── GET /admin/v1/metrics/{code}/sources（版本无关血缘）──────────────────────

    @Test
    void sources_returns200_withMetricSourcesResponse() throws Exception {
        List<RuleRef> refs = List.of(
                new RuleRef(10L, "risk.transfer", "转账风控", "risk.transfer", "ACTIVE"),
                new RuleRef(11L, "risk.login",    "登录风控", "risk.login",    "DISABLED")
        );
        when(service.findRulesReferencingMetric(1L, "account.age")).thenReturn(refs);

        mockMvc.perform(get("/admin/v1/metrics/account.age/sources").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.metricCode").value("account.age"))
                .andExpect(jsonPath("$.data.sourceCount").value(2))
                .andExpect(jsonPath("$.data.sources").isArray())
                .andExpect(jsonPath("$.data.sources.length()").value(2))
                .andExpect(jsonPath("$.data.sources[0].ruleCode").value("risk.transfer"))
                .andExpect(jsonPath("$.data.sources[0].sceneCode").value("risk.transfer"))
                .andExpect(jsonPath("$.data.sources[1].ruleName").value("登录风控"));

        verify(service).findRulesReferencingMetric(1L, "account.age");
    }

    @Test
    void sources_missingTenantId_returns400() throws Exception {
        mockMvc.perform(get("/admin/v1/metrics/account.age/sources"))
                .andExpect(status().isBadRequest());
    }
}
