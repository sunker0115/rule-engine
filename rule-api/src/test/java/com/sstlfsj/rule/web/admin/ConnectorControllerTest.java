package com.sstlfsj.rule.web.admin;

import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.connector.CompareOp;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.connector.HttpMethod;
import com.sstlfsj.rule.config.api.connector.HttpRequestTemplate;
import com.sstlfsj.rule.config.api.connector.Predicate;
import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.ResponseMapping;
import com.sstlfsj.rule.config.api.connector.StaticHeaderAuth;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorDetailView;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorView;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorWriteCommand;
import com.sstlfsj.rule.eval.api.FetchTrace;
import com.sstlfsj.rule.eval.api.service.MetricFetchTestService;
import com.sstlfsj.rule.web.admin.convert.ConnectorConvert;
import com.sstlfsj.rule.web.admin.convert.ConnectorConvertImpl;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** ConnectorController 单元测试：list / create / update 路由与委托。create 的 connectorCode 以 query param 传入。 */
class ConnectorControllerTest {

    private MockMvc mockMvc;
    private ConnectorWriteService service;
    private MetricFetchTestService testService;

    private static final String DESCRIPTOR_JSON = """
            {"name":"风控打分","descriptor":{
              "endpointRef":"risk",
              "request":{"method":"GET","pathTemplate":"/s/{payload.id}","query":[],"headers":[],"bodyTemplate":null},
              "response":{"successWhen":{"path":"ok","op":"EQ","value":true},"valuePath":"v"},
              "auth":{"kind":"STATIC_HEADER","headerName":"X-Key","credentialRef":"k"},
              "resilience":{"connectTimeoutMs":200,"readTimeoutMs":300,"retries":0,"retryOn":[],"circuitBreaker":null},
              "errorMapping":[]}}""";

    @BeforeEach
    void setUp() {
        service = mock(ConnectorWriteService.class);
        testService = mock(MetricFetchTestService.class);
        ConnectorConvert convert = new ConnectorConvertImpl();
        JsonMapper mapper = JsonMapper.builder().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ConnectorController(service, convert, testService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    // ── GET /admin/v1/connectors ────────────────────────────────────────────────

    @Test
    void listReturnsActiveConnectors() throws Exception {
        when(service.listActive(1L)).thenReturn(List.of(
                new ConnectorView("risk-svc", "风控打分", "ACTIVE", "2026-06-16T00:00", null)));

        mockMvc.perform(get("/admin/v1/connectors").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].connectorCode").value("risk-svc"))
                .andExpect(jsonPath("$.data[0].name").value("风控打分"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));

        verify(service).listActive(1L);
    }

    // ── POST /admin/v1/connectors ───────────────────────────────────────────────

    @Test
    void createReturns201AndId() throws Exception {
        when(service.create(eq(1L), eq("risk-svc"), any(), eq("u1"))).thenReturn(99L);

        mockMvc.perform(post("/admin/v1/connectors")
                        .param("tenantId", "1")
                        .param("connectorCode", "risk-svc")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DESCRIPTOR_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(99));

        verify(service).create(eq(1L), eq("risk-svc"), any(ConnectorWriteCommand.class), eq("u1"));
    }

    @Test
    void createMissingTenantIdReturns400() throws Exception {
        mockMvc.perform(post("/admin/v1/connectors")
                        .param("connectorCode", "risk-svc")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /admin/v1/connectors/{connectorCode} ────────────────────────────────

    @Test
    void updateReturns200AndAffectedRows() throws Exception {
        when(service.update(eq(1L), eq("risk-svc"), any(), eq("u1"))).thenReturn(1);

        mockMvc.perform(put("/admin/v1/connectors/risk-svc")
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DESCRIPTOR_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(1));

        verify(service).update(eq(1L), eq("risk-svc"), any(ConnectorWriteCommand.class), eq("u1"));
    }

    @Test
    void updateIllegalArgumentReturns400() throws Exception {
        when(service.update(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("connector 不存在: risk-svc"));

        mockMvc.perform(put("/admin/v1/connectors/risk-svc")
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DESCRIPTOR_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── GET /admin/v1/connectors/{connectorCode} ────────────────────────────────

    @Test
    void getByCodeReturnsDetailWithTypedDescriptor() throws Exception {
        when(service.getByCode(1L, "risk-svc")).thenReturn(
                new ConnectorDetailView("risk-svc", "风控打分", descriptor(), "DISABLED"));

        mockMvc.perform(get("/admin/v1/connectors/risk-svc").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.connectorCode").value("risk-svc"))
                .andExpect(jsonPath("$.data.name").value("风控打分"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                // descriptor 以 typed 对象序列化，不是 String
                .andExpect(jsonPath("$.data.descriptor.endpointRef").value("risk"))
                .andExpect(jsonPath("$.data.descriptor.request.method").value("GET"));

        verify(service).getByCode(1L, "risk-svc");
    }

    @Test
    void getByCodeMissingReturns400() throws Exception {
        when(service.getByCode(1L, "risk-svc"))
                .thenThrow(new IllegalArgumentException("连接器不存在: risk-svc"));

        mockMvc.perform(get("/admin/v1/connectors/risk-svc").param("tenantId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── POST /admin/v1/connectors/{connectorCode}/disable ───────────────────────

    @Test
    void disableReturns200AndDelegates() throws Exception {
        mockMvc.perform(post("/admin/v1/connectors/risk-svc/disable")
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).disable(1L, "risk-svc", "u1");
    }

    @Test
    void disableMissingReturns400() throws Exception {
        doThrow(new IllegalArgumentException("连接器不存在: risk-svc"))
                .when(service).disable(1L, "risk-svc", "u1");

        mockMvc.perform(post("/admin/v1/connectors/risk-svc/disable")
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "u1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── POST /admin/v1/connectors/{connectorCode}:test ──────────────────────────

    @Test
    void testEndpointColonRouteHitsMethodAndReturnsTrace() throws Exception {
        // 验收门：冒号风格路由 :test 真命中方法（不 404），返回分阶段 FetchTrace
        when(testService.testConnector(eq(1L), eq("risk-svc"), any(), any(), eq("s1")))
                .thenReturn(new FetchTrace("EXTERNAL_HTTP", "GET https://risk/s/9",
                        null, "{\"ok\":true,\"v\":42}", true, 42, null));

        mockMvc.perform(post("/admin/v1/connectors/risk-svc:test")
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

        verify(testService).testConnector(eq(1L), eq("risk-svc"), any(), any(), eq("s1"));
    }

    // typed descriptor 工厂：与 DESCRIPTOR_JSON 同形，供 detail 端点返回体断言
    private static ConnectorDescriptor descriptor() {
        return ConnectorDescriptor.builder()
                .endpointRef("risk")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.GET).pathTemplate("/s/{payload.id}")
                        .query(List.of()).headers(List.of()).bodyTemplate(null).build())
                .response(new ResponseMapping(new Predicate("ok", CompareOp.EQ, true), "v"))
                .auth(new StaticHeaderAuth("X-Key", "k"))
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of()).build();
    }
}
