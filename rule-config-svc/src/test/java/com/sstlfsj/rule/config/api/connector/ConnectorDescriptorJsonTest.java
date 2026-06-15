package com.sstlfsj.rule.config.api.connector;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorDescriptorJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void roundTripsWithOAuth2AndErrorMapping() {
        ConnectorDescriptor d = ConnectorDescriptor.builder()
                .endpointRef("risk-svc")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.POST)
                        .pathTemplate("/score/{subjectId}")
                        .query(List.of(new TemplateParam("region", "{payload.region}")))
                        .headers(List.of(new TemplateParam("X-Trace", "{now}")))
                        .bodyTemplate("{\"f\":\"{vars.feature}\"}")
                        .build())
                .response(new ResponseMapping(new Predicate("code", CompareOp.EQ, 0), "data.score"))
                .auth(OAuth2ClientCredentialsAuth.builder()
                        .tokenUrl("https://auth/token").clientIdRef("cid").clientSecretRef("sec")
                        .scopes(List.of("score")).build())
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(1)
                        .retryOn(Set.of(RetryTrigger.TIMEOUT))
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50).windowSeconds(10).openSeconds(30).build())
                        .build())
                .errorMapping(List.of(new ErrorRule(new ErrorMatch(500, 599, null), "UPSTREAM_ERROR")))
                .build();

        String json = mapper.writeValueAsString(d);
        ConnectorDescriptor back = mapper.readValue(json, ConnectorDescriptor.class);

        assertThat(back).isEqualTo(d);
        assertThat(back.auth()).isInstanceOf(OAuth2ClientCredentialsAuth.class);
        assertThat(back.auth().kind()).isEqualTo(AuthKind.OAUTH2_CLIENT_CREDENTIALS);
        assertThat(json).contains("\"kind\":\"OAUTH2_CLIENT_CREDENTIALS\"");
    }

    @Test
    void roundTripsStaticHeaderGet() {
        ConnectorDescriptor d = ConnectorDescriptor.builder()
                .endpointRef("ip-rep")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.GET).pathTemplate("/ip/{payload.ip}")
                        .query(List.of()).headers(List.of()).bodyTemplate(null).build())
                .response(new ResponseMapping(new Predicate("ok", CompareOp.EQ, true), "rep"))
                .auth(new StaticHeaderAuth("X-Api-Key", "ipRepKey"))
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of())
                .build();

        ConnectorDescriptor back = mapper.readValue(mapper.writeValueAsString(d), ConnectorDescriptor.class);
        assertThat(back).isEqualTo(d);
        assertThat(back.auth()).isInstanceOf(StaticHeaderAuth.class);
    }
}
