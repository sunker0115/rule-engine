package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sstlfsj.rule.config.api.connector.BearerAuth;
import com.sstlfsj.rule.config.api.connector.CompareOp;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.connector.HttpMethod;
import com.sstlfsj.rule.config.api.connector.HttpRequestTemplate;
import com.sstlfsj.rule.config.api.connector.Predicate;
import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.ResponseMapping;
import com.sstlfsj.rule.eval.internal.metric.http.ConnectorDefinitionResolver;
import com.sstlfsj.rule.eval.internal.metric.http.CredentialStore;
import com.sstlfsj.rule.eval.internal.metric.http.DeclarativeHttpConnectorHandler;
import com.sstlfsj.rule.eval.internal.metric.http.HttpEndpointRegistry;
import com.sstlfsj.rule.eval.internal.metric.http.OAuth2TokenManager;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import com.sstlfsj.rule.eval.internal.metric.sql.MetricDataSourceRegistry;
import com.sstlfsj.rule.eval.internal.metric.sql.SqlAggregateMetricSourceHandler;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MetricFetchTester 跨源自助测试器测试：HTTP（WireMock 桩，断言分阶段 trace）+ SQL（mock 数据源，
 * 断言 boundSql / coercedValue）+ HTTP 失败路径（上游 500 → UPSTREAM_ERROR）+ connector 直测重载。
 */
class MetricFetchTesterTest {

    private static final String TENANT = "1";
    private static final long TENANT_ID = 1L;
    private static final String CONNECTOR = "risk-svc";
    private static final String ENDPOINT_REF = "risk";

    private WireMockServer wireMock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ConnectorDefinitionResolver connectorResolver;
    private OAuth2TokenManager oauth2TokenManager;
    private MetricDefinitionResolver metricResolver;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        connectorResolver = mock(ConnectorDefinitionResolver.class);
        oauth2TokenManager = mock(OAuth2TokenManager.class);
        metricResolver = mock(MetricDefinitionResolver.class);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void test_http_success_capturesPhasedTrace() {
        when(connectorResolver.resolve(TENANT_ID, CONNECTOR)).thenReturn(httpDescriptor());
        when(metricResolver.resolve(TENANT, "risk_score", 1)).thenReturn(httpMetric());
        wireMock.stubFor(post(urlEqualTo("/score/sub1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":0,\"data\":{\"score\":88}}")));

        MetricFetchTester tester = tester(httpHandler(), sqlHandler(mock(NamedParameterJdbcTemplate.class)));
        FetchTrace trace = tester.test(TENANT_ID, "risk_score", Map.of(), Map.of(), "sub1");

        assertThat(trace.sourceType()).isEqualTo(SourceType.EXTERNAL_HTTP);
        assertThat(trace.renderedRequest()).contains("/score/sub1");
        assertThat(trace.rawResponse()).contains("\"score\":88");
        assertThat(trace.successMatched()).isTrue();
        assertThat(trace.mappedValue()).isEqualTo(88L);
        assertThat(trace.errorCode()).isNull();
    }

    @Test
    void test_http_upstream500_capturesUpstreamError() {
        when(connectorResolver.resolve(TENANT_ID, CONNECTOR)).thenReturn(httpDescriptor());
        when(metricResolver.resolve(TENANT, "risk_score", 1)).thenReturn(httpMetric());
        wireMock.stubFor(post(urlEqualTo("/score/sub1")).willReturn(aResponse().withStatus(500)));

        MetricFetchTester tester = tester(httpHandler(), sqlHandler(mock(NamedParameterJdbcTemplate.class)));
        FetchTrace trace = tester.test(TENANT_ID, "risk_score", Map.of(), Map.of(), "sub1");

        assertThat(trace.sourceType()).isEqualTo(SourceType.EXTERNAL_HTTP);
        assertThat(trace.renderedRequest()).contains("/score/sub1");
        assertThat(trace.errorCode()).isEqualTo(MetricFetchError.UPSTREAM_ERROR.tag());
        assertThat(trace.mappedValue()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_sql_success_capturesBoundSqlAndCoercedValue() {
        NamedParameterJdbcTemplate tpl = mock(NamedParameterJdbcTemplate.class);
        when(tpl.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of((Object) 42L));
        when(metricResolver.resolve(TENANT, "txn_count", 1)).thenReturn(sqlMetric());

        MetricFetchTester tester = tester(httpHandler(), sqlHandler(tpl));
        FetchTrace trace = tester.test(TENANT_ID, "txn_count", Map.of(), Map.of(), "u1");

        assertThat(trace.sourceType()).isEqualTo(SourceType.SQL_AGGREGATE);
        assertThat(trace.boundSql()).contains(":subjectId");
        assertThat(trace.mappedValue()).isEqualTo(42L);
        assertThat(trace.errorCode()).isNull();
    }

    @Test
    void testConnector_http_success_capturesPhasedTrace() {
        when(connectorResolver.resolve(TENANT_ID, CONNECTOR)).thenReturn(httpDescriptor());
        wireMock.stubFor(post(urlEqualTo("/score/sub1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":0,\"data\":{\"score\":88}}")));

        MetricFetchTester tester = tester(httpHandler(), sqlHandler(mock(NamedParameterJdbcTemplate.class)));
        FetchTrace trace = tester.testConnector(TENANT_ID, CONNECTOR,
                Map.of(), Map.of(), "sub1");

        assertThat(trace.sourceType()).isEqualTo(SourceType.EXTERNAL_HTTP);
        assertThat(trace.renderedRequest()).contains("/score/sub1");
        assertThat(trace.rawResponse()).contains("\"score\":88");
        assertThat(trace.mappedValue()).isEqualTo(88L);
        assertThat(trace.errorCode()).isNull();
    }

    // ---- 桩与工厂 ----

    private MetricFetchTester tester(DeclarativeHttpConnectorHandler http, SqlAggregateMetricSourceHandler sql) {
        return new MetricFetchTester(List.of((MetricSourceHandler) http, sql), metricResolver, connectorResolver);
    }

    private DeclarativeHttpConnectorHandler httpHandler() {
        return new DeclarativeHttpConnectorHandler(
                endpointRegistry(), connectorResolver, credentialStore(), oauth2TokenManager, objectMapper);
    }

    private SqlAggregateMetricSourceHandler sqlHandler(NamedParameterJdbcTemplate tpl) {
        MetricDataSourceRegistry registry = mock(MetricDataSourceRegistry.class);
        when(registry.template("risk")).thenReturn(tpl);
        return new SqlAggregateMetricSourceHandler(registry);
    }

    /** HTTP metric 定义：sourceType=EXTERNAL_HTTP，params 含 connector + dataType（resolver 注入语义）。 */
    private MetricDescriptor httpMetric() {
        return new MetricDescriptor("risk_score", 1, SourceType.EXTERNAL_HTTP, "LONG",
                false, 0, Map.of("connector", CONNECTOR, "dataType", "LONG"));
    }

    /** SQL metric 定义：sourceType=SQL_AGGREGATE，params 含 datasource/sql/dataType。 */
    private MetricDescriptor sqlMetric() {
        return new MetricDescriptor("txn_count", 1, SourceType.SQL_AGGREGATE, "LONG",
                false, 0, Map.of("datasource", "risk",
                        "sql", "SELECT COUNT(*) FROM t WHERE uid = :subjectId", "dataType", "LONG"));
    }

    private ConnectorDescriptor httpDescriptor() {
        return ConnectorDescriptor.builder()
                .endpointRef(ENDPOINT_REF)
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.POST)
                        .pathTemplate("/score/{subjectId}")
                        .build())
                .response(new ResponseMapping(new Predicate("code", CompareOp.EQ, 0), "data.score"))
                .auth(new BearerAuth("tok-ref"))
                .resilience(ResiliencePolicy.builder().connectTimeoutMs(1000).readTimeoutMs(2000).build())
                .build();
    }

    private HttpEndpointRegistry endpointRegistry() {
        FetchResourceProperties props = new FetchResourceProperties();
        FetchResourceProperties.EndpointDef ep = new FetchResourceProperties.EndpointDef();
        ep.setName(ENDPOINT_REF);
        ep.setBaseUrl("http://localhost:" + wireMock.port());
        props.setEndpoints(List.of(ep));
        return new HttpEndpointRegistry(props);
    }

    private CredentialStore credentialStore() {
        FetchResourceProperties props = new FetchResourceProperties();
        FetchResourceProperties.CredentialDef def = new FetchResourceProperties.CredentialDef();
        def.setName("tok-ref");
        def.setValue("secret-token");
        props.setCredentials(List.of(def));
        return new CredentialStore(props);
    }
}
