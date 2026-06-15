package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.api.connector.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConnectorSafetyValidatorTest {

    private final ConnectorSafetyValidator validator = new ConnectorSafetyValidator();

    private ConnectorDescriptor descriptor(String endpointRef, String pathTemplate) {
        return ConnectorDescriptor.builder()
                .endpointRef(endpointRef)
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.GET).pathTemplate(pathTemplate)
                        .query(List.of()).headers(List.of()).bodyTemplate(null).build())
                .response(new ResponseMapping(new Predicate("ok", CompareOp.EQ, true), "v"))
                .auth(new StaticHeaderAuth("X-Key", "k"))
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of())
                .build();
    }

    @Test
    void rejectsUnregisteredEndpoint() {
        assertThatThrownBy(() -> validator.validate(
                descriptor("ghost", "/x"), Set.of("known")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void rejectsUnknownPlaceholderNamespace() {
        assertThatThrownBy(() -> validator.validate(
                descriptor("known", "/x/{bogus.id}"), Set.of("known")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
    }

    @Test
    void acceptsValidDescriptor() {
        assertThatCode(() -> validator.validate(
                descriptor("known", "/x/{payload.id}/{vars.q}/{subject.level}/{now}"), Set.of("known")))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsEndpointCheckWhenNamesNull() {
        assertThatCode(() -> validator.validate(descriptor("any", "/x"), null))
                .doesNotThrowAnyException();
    }
}
