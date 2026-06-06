package com.sstlfsj.rule.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDefinitionPollerTest {

    @Test
    void buildUrl_declaredWithScenes_appendsScenes() {
        String url = MetricDefinitionPoller.buildUrl(
                "http://h:8080", "t1", FetchMode.DECLARED, List.of("payment", "fraud"));
        assertThat(url).isEqualTo(
                "http://h:8080/api/v1/sdk/metric-definitions?tenantId=t1&scenes=payment,fraud");
    }

    @Test
    void buildUrl_allMode_ignoresScenes() {
        String url = MetricDefinitionPoller.buildUrl(
                "http://h:8080", "t1", FetchMode.ALL, List.of("payment"));
        assertThat(url).isEqualTo("http://h:8080/api/v1/sdk/metric-definitions?tenantId=t1");
    }

    @Test
    void buildUrl_declaredEmptyScenes_noScenesParam() {
        String url = MetricDefinitionPoller.buildUrl(
                "http://h:8080", "t1", FetchMode.DECLARED, List.of());
        assertThat(url).isEqualTo("http://h:8080/api/v1/sdk/metric-definitions?tenantId=t1");
    }
}
