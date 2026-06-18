package com.sstlfsj.rule.eval.internal.metric.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sstlfsj.rule.config.api.connector.OAuth2ClientCredentialsAuth;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2TokenManagerTest {

    private WireMockServer wireMock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 凭证库桩：clientIdRef→"cid"、clientSecretRef→"sec"，其余 null。 */
    private static FetchResourceProperties propsWithClientCreds() {
        FetchResourceProperties props = new FetchResourceProperties();
        props.setCredentials(List.of(cred("cid-ref", "cid"), cred("sec-ref", "sec")));
        return props;
    }

    private static FetchResourceProperties.CredentialDef cred(String name, String value) {
        FetchResourceProperties.CredentialDef def = new FetchResourceProperties.CredentialDef();
        def.setName(name);
        def.setValue(value);
        return def;
    }

    private OAuth2ClientCredentialsAuth authAt(int port) {
        return OAuth2ClientCredentialsAuth.builder()
                .tokenUrl("http://localhost:" + port + "/token")
                .clientIdRef("cid-ref")
                .clientSecretRef("sec-ref")
                .scopes(List.of("score"))
                .build();
    }

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    @Test
    void token_fetchesAccessTokenFromEndpoint() {
        wireMock.stubFor(post(urlEqualTo("/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"tok-1\",\"expires_in\":3600}")));
        OAuth2TokenManager mgr = new OAuth2TokenManager(new CredentialStore(propsWithClientCreds()), objectMapper);

        assertThat(mgr.token(authAt(wireMock.port()))).isEqualTo("tok-1");
    }

    @Test
    void token_secondCallHitsCache_onlyOneTokenRequest() {
        wireMock.stubFor(post(urlEqualTo("/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"tok-1\",\"expires_in\":3600}")));
        OAuth2TokenManager mgr = new OAuth2TokenManager(new CredentialStore(propsWithClientCreds()), objectMapper);
        OAuth2ClientCredentialsAuth auth = authAt(wireMock.port());

        assertThat(mgr.token(auth)).isEqualTo("tok-1");
        assertThat(mgr.token(auth)).isEqualTo("tok-1");

        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void token_responseMissingAccessToken_throwsWithoutBody() {
        wireMock.stubFor(post(urlEqualTo("/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));
        OAuth2TokenManager mgr = new OAuth2TokenManager(new CredentialStore(propsWithClientCreds()), objectMapper);

        assertThatThrownBy(() -> mgr.token(authAt(wireMock.port())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access_token")
                .hasMessageNotContaining("{}");
    }

    @Test
    void token_missingCredential_throws() {
        OAuth2TokenManager mgr = new OAuth2TokenManager(new CredentialStore(new FetchResourceProperties()), objectMapper);

        assertThatThrownBy(() -> mgr.token(authAt(wireMock.port())))
                .isInstanceOf(CredentialMissingException.class);
    }
}
