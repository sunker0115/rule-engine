package com.sstlfsj.rule.eval.internal.metric.http;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalHttpMetricSourceHandlerTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void renderPath_substitutesAndUrlEncodes() {
        String path = ExternalHttpMetricSourceHandler.renderPath(
                "/score/{payload.uid}/{params.kind}", Map.of("uid", "a b"), Map.of("kind", "risk"));
        assertThat(path).isEqualTo("/score/a%20b/risk");
    }

    @Test
    void extractJsonPath_navigatesDotPath() {
        var node = om.readTree("{\"data\":{\"balance\":1234}}");
        Object v = ExternalHttpMetricSourceHandler.extractJsonPath(node, "data.balance");
        assertThat(v).isEqualTo(1234);
    }

    @Test
    void extractJsonPath_missing_returnsNull() {
        var node = om.readTree("{\"data\":{}}");
        assertThat(ExternalHttpMetricSourceHandler.extractJsonPath(node, "data.balance")).isNull();
    }
}
