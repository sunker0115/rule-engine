package com.sstlfsj.rule.eval.internal.metric.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sstlfsj.rule.config.api.connector.AuthScheme;
import com.sstlfsj.rule.config.api.connector.BearerAuth;
import com.sstlfsj.rule.config.api.connector.CompareOp;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.connector.HttpMethod;
import com.sstlfsj.rule.config.api.connector.HttpRequestTemplate;
import com.sstlfsj.rule.config.api.connector.OAuth2ClientCredentialsAuth;
import com.sstlfsj.rule.config.api.connector.Predicate;
import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.ResponseMapping;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DeclarativeHttpConnectorHandler WireMock 测试：覆盖成功取值、上游错误、successWhen 不命中、
 * valuePath 缺失、OAuth2 Bearer 注入与凭证缺失五类断言。
 */
class DeclarativeHttpConnectorHandlerTest {

    private static final String CONNECTOR = "risk-svc";
    private static final String ENDPOINT_REF = "risk";

    private WireMockServer wireMock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ConnectorDefinitionResolver resolver;
    private OAuth2TokenManager oauth2TokenManager;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        resolver = mock(ConnectorDefinitionResolver.class);
        oauth2TokenManager = mock(OAuth2TokenManager.class);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    /** 端点注册表桩：注册名 risk → baseUrl 指向 WireMock。 */
    private HttpEndpointRegistry endpointRegistry() {
        FetchResourceProperties props = new FetchResourceProperties();
        FetchResourceProperties.EndpointDef ep = new FetchResourceProperties.EndpointDef();
        ep.setName(ENDPOINT_REF);
        ep.setBaseUrl("http://localhost:" + wireMock.port());
        props.setEndpoints(List.of(ep));
        return new HttpEndpointRegistry(props);
    }

    /** 凭证库桩：tok-ref → "secret-token"。 */
    private CredentialStore credentialStore() {
        FetchResourceProperties props = new FetchResourceProperties();
        FetchResourceProperties.CredentialDef def = new FetchResourceProperties.CredentialDef();
        def.setName("tok-ref");
        def.setValue("secret-token");
        props.setCredentials(List.of(def));
        return new CredentialStore(props);
    }

    /** 空凭证库（用于凭证缺失断言）。 */
    private CredentialStore emptyCredentialStore() {
        return new CredentialStore(new FetchResourceProperties());
    }

    /** 描述符：POST /score/{subjectId}，successWhen code EQ 0，valuePath data.score。 */
    private ConnectorDescriptor descriptor(AuthScheme auth) {
        return ConnectorDescriptor.builder()
                .endpointRef(ENDPOINT_REF)
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.POST)
                        .pathTemplate("/score/{subjectId}")
                        .build())
                .response(new ResponseMapping(new Predicate("code", CompareOp.EQ, 0), "data.score"))
                .auth(auth)
                .resilience(ResiliencePolicy.builder().connectTimeoutMs(1000).readTimeoutMs(2000).build())
                .build();
    }

    private DeclarativeHttpConnectorHandler handler(CredentialStore credentialStore) {
        return new DeclarativeHttpConnectorHandler(
                endpointRegistry(), resolver, credentialStore, oauth2TokenManager, objectMapper);
    }

    private MetricQuery query() {
        return new MetricQuery("risk_score", "1", "sub1",
                Map.of("connector", CONNECTOR, "dataType", "LONG"), Map.of(), Instant.now());
    }

    @Test
    void success_returnsFetchedValue() {
        when(resolver.resolve(1L, CONNECTOR)).thenReturn(descriptor(new BearerAuth("tok-ref")));
        wireMock.stubFor(post(urlEqualTo("/score/sub1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":0,\"data\":{\"score\":88}}")));

        MetricValue v = handler(credentialStore()).fetch(query());

        assertThat(v.isError()).isFalse();
        assertThat(v.value()).isEqualTo(88L);
        assertThat(v.valueSource()).isEqualTo(ValueSource.FETCHED.tag());
        assertThat(v.errorCode()).isNull();
    }

    @Test
    void upstream500_returnsUpstreamError() {
        when(resolver.resolve(1L, CONNECTOR)).thenReturn(descriptor(new BearerAuth("tok-ref")));
        wireMock.stubFor(post(urlEqualTo("/score/sub1")).willReturn(aResponse().withStatus(500)));

        MetricValue v = handler(credentialStore()).fetch(query());

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo(MetricFetchError.UPSTREAM_ERROR.tag());
    }

    @Test
    void successWhenNotMatched_returnsUpstreamError() {
        when(resolver.resolve(1L, CONNECTOR)).thenReturn(descriptor(new BearerAuth("tok-ref")));
        wireMock.stubFor(post(urlEqualTo("/score/sub1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":1,\"data\":{\"score\":88}}")));

        MetricValue v = handler(credentialStore()).fetch(query());

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo(MetricFetchError.UPSTREAM_ERROR.tag());
    }

    @Test
    void valuePathMissing_returnsParseError() {
        when(resolver.resolve(1L, CONNECTOR)).thenReturn(descriptor(new BearerAuth("tok-ref")));
        wireMock.stubFor(post(urlEqualTo("/score/sub1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":0,\"data\":{}}")));

        MetricValue v = handler(credentialStore()).fetch(query());

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo(MetricFetchError.PARSE_ERROR.tag());
    }

    @Test
    void oauth2_injectsBearerToken_andSucceeds() {
        OAuth2ClientCredentialsAuth auth = OAuth2ClientCredentialsAuth.builder()
                .tokenUrl("http://localhost:" + wireMock.port() + "/token")
                .clientIdRef("cid").clientSecretRef("sec").scopes(List.of("score")).build();
        when(resolver.resolve(1L, CONNECTOR)).thenReturn(descriptor(auth));
        when(oauth2TokenManager.token(auth)).thenReturn("tok-x");
        wireMock.stubFor(post(urlEqualTo("/score/sub1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":0,\"data\":{\"score\":88}}")));

        MetricValue v = handler(credentialStore()).fetch(query());

        assertThat(v.isError()).isFalse();
        assertThat(v.value()).isEqualTo(88L);
        wireMock.verify(postRequestedFor(urlEqualTo("/score/sub1"))
                .withHeader("Authorization", equalTo("Bearer tok-x")));
    }

    @Test
    void oauth2_tokenExchangeFails_returnsUnauthorized() {
        OAuth2ClientCredentialsAuth auth = OAuth2ClientCredentialsAuth.builder()
                .tokenUrl("http://localhost:" + wireMock.port() + "/token")
                .clientIdRef("cid").clientSecretRef("sec").build();
        when(resolver.resolve(1L, CONNECTOR)).thenReturn(descriptor(auth));
        when(oauth2TokenManager.token(auth)).thenThrow(new CredentialMissingException("cid"));

        MetricValue v = handler(emptyCredentialStore()).fetch(query());

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo(MetricFetchError.UNAUTHORIZED.tag());
    }

    @Test
    void connectorNotFound_returnsNotFound() {
        when(resolver.resolve(1L, CONNECTOR)).thenReturn(null);

        MetricValue v = handler(credentialStore()).fetch(query());

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo(MetricFetchError.NOT_FOUND.tag());
    }
}
