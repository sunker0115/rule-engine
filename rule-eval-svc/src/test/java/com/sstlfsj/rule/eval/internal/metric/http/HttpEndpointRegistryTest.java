package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpEndpointRegistryTest {

    @Test
    void registersByName() {
        FetchResourceProperties.EndpointDef def = new FetchResourceProperties.EndpointDef();
        def.setName("kyc");
        def.setBaseUrl("https://kyc.internal");
        def.setAuthHeaderName("X-Api-Key");
        def.setAuthHeaderValue("secret");
        FetchResourceProperties props = new FetchResourceProperties();
        props.setEndpoints(List.of(def));

        HttpEndpointRegistry reg = new HttpEndpointRegistry(props);

        assertThat(reg.get("kyc")).isNotNull();
        assertThat(reg.get("kyc").baseUrl()).isEqualTo("https://kyc.internal");
        assertThat(reg.names()).containsExactly("kyc");
        assertThat(reg.get("absent")).isNull();
    }
}
