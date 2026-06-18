package com.sstlfsj.rule.config.api.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorEnumsTest {

    @Test
    void httpMethodHasExpectedValues() {
        assertThat(HttpMethod.values()).containsExactly(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT);
    }

    @Test
    void authKindHasThreeSchemes() {
        assertThat(AuthKind.values()).containsExactly(
                AuthKind.STATIC_HEADER, AuthKind.BEARER, AuthKind.OAUTH2_CLIENT_CREDENTIALS);
    }

    @Test
    void compareOpAndRetryTriggerResolveByName() {
        assertThat(CompareOp.valueOf("GE")).isEqualTo(CompareOp.GE);
        assertThat(RetryTrigger.valueOf("UPSTREAM_5XX")).isEqualTo(RetryTrigger.UPSTREAM_5XX);
    }
}
